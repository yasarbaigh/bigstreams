package org.streams.streamslog.log.file

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions._
import org.apache.commons.codec.binary.Base64
import org.apache.log4j.Logger
import org.streams.commons.compression.CompressionPool
import org.streams.commons.compression.CompressionPoolFactory
import org.streams.commons.compression.impl.CompressionPoolFactoryImpl
import org.streams.commons.status.Status
import org.streams.commons.status.Status.STATUS
import org.streams.streamslog.jmx.JMXHelpers._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.gracefulStop
import akka.dispatch.Await
import akka.util.Duration
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.Terminated
import akka.actor.AllForOneStrategy
import akka.actor.SupervisorStrategy._
import akka.actor.SupervisorStrategy

object FileLogResource {

  val logger = Logger.getLogger(getClass())
  
  val system = ActorSystem("FileLogResourceSystem")

  def shutdown() = {
	  system.shutdown
	  system.awaitTermination(Duration(20, TimeUnit.SECONDS))
  }
  
  def stopActor(actorRef:ActorRef) = {
    try {
      val stopped: Future[Boolean] = gracefulStop(actorRef, Duration(10, TimeUnit.SECONDS))(system)
      Await.result(stopped, Duration(10, TimeUnit.SECONDS))
      // the actor has been stopped
    } catch {
      case e: Throwable => logger.error(e.toString(), e) 
    }
    
  }
  
}

/**
 * Manages File Writers for topics, each FileWriter in turn manages its files based on date.<br/>
 *
 */
class FileLogResource(topics: Map[String, TopicConfig], compressors: Int = 100) {

  val logger = Logger.getLogger(classOf[FileLogResource])
  val statusActor = StatusActor()

  //check that all directories do exist
  for (topic <- topics.values) {
    if (!topic.baseDir.exists()) topic.baseDir.mkdirs()

    if (!(topic.baseDir.exists() && topic.baseDir.canWrite()))
      throw new RuntimeException("The directory " + topic.baseDir.getAbsolutePath() + " does not exist or is not writable")
  }

  val openWriters = new ConcurrentHashMap[String, ActorRef]()
  val compressionPoolFactory = new CompressionPoolFactoryImpl(compressors, compressors, NonStatus)

  val execSerivce = Executors.newScheduledThreadPool(2)

  execSerivce.scheduleWithFixedDelay(new RollService(), 10L, 2L, TimeUnit.SECONDS)
  execSerivce.scheduleWithFixedDelay(new StatusPrintService(statusActor), 10L, 10L, TimeUnit.SECONDS)

  def get(topic: String) = {
    openWriters.synchronized {
      val writer = if (openWriters.containsKey(topic)) { openWriters(topic) } else { val w = createWriter(topic); openWriters.put(topic, w); w }

      //     if (writer.criticalError.get())
      //      throw new RuntimeException("Critical errors while writing to filesystem")

      writer
    }
  }

  def createWriter(topic: String) = {
    LogFileWriter(topics(topic), compressionPoolFactory, statusActor)
  }

  def close() = {
    execSerivce.shutdownNow()
    for (writer <- openWriters.values) {
      logger.info("Stopping writer: " + writer)
      writer ! 'stop
    }
  }

  /**
   * Service that is woken up with a Scheduled Fixed Delay to notify each LogFileWriter to check for files that need rolling.
   */
  class RollService extends Runnable {

    override def run() = {
      try {
        val writers = openWriters.synchronized { openWriters.values().toArray(Array[ActorRef]()) }

        for (writer <- writers)
          writer ! 'checkRolls

      } catch {
        case e => logger.error(e.toString(), e)
      }
    }

  }

  class StatusPrintService(statusActor: ActorRef) extends Runnable {
    val logger = Logger.getLogger(classOf[StatusPrintService])

    override def run() = {
      try {
        statusActor ! ('log, logger)
      } catch {
        case e => logger.error(e.toString(), e)
      }
    }

  }

}

trait StatusActorMBean {
  def topics(): Array[String]
  def topicStatus(topic: String): Long
}

object StatusActor {
  def apply() = {
    FileLogResource.system.actorOf(Props(new StatusActor()))
  }
}

class StatusActor extends Actor with StatusActorMBean {

  jmxRegister(this, "JMXFileLogResource:name=LogConsumerStatus")
  val messageReceivedTSMap = collection.mutable.Map[String, Long]()

  override def topics() = messageReceivedTSMap.keys.toArray
  override def topicStatus(topic: String) = messageReceivedTSMap(topic)

  def receive = {
    case ('log, logger: Logger) =>
      printDetails(logger)
    case topic: String =>
      messageReceivedTSMap(topic) = System.currentTimeMillis()
    case m: Any =>
      println("Could not understand: " + m)
  }

  def printDetails(log: Logger) = {
    log.info("Message consume update : " + messageReceivedTSMap.size + " topics")
    val currTS = System.currentTimeMillis()
    for ((topic, ts) <- messageReceivedTSMap) {
      log.info(topic + " received messages " + (currTS - ts) + "ms ago")
    }

  }

}

object LogFileWriter {

  def apply(topicConfig: TopicConfig, compressionPoolFactory: CompressionPoolFactory, statusActor: ActorRef = null) = {
    FileLogResource.system.actorOf(Props(new LogFileWriter(topicConfig, compressionPoolFactory, statusActor)), "logWriter-" + topicConfig.topic)
  }

}
/**
 * Actor that handles the reading and writing of files determined by date for a topic.<br/>
 * Each file for the topic is itself handled as an Actor wrapped by the FileObj instance.<br/>
 */
class LogFileWriter(topicConfig: TopicConfig, compressionPoolFactory: CompressionPoolFactory, statusActor: ActorRef = null) extends Actor {
  val logger = Logger.getLogger(classOf[LogFileWriter])

  val baseDir = topicConfig.baseDir
  //ensure that the directory is created
  //  baseDir.mkdirs()

  if (!(baseDir.exists() && baseDir.canWrite()))
    throw new RuntimeException(baseDir + " does not exist or is not writable")

  val topic = topicConfig.topic
  val compressionPool = compressionPoolFactory.get(topicConfig.codec)
  val extension = topicConfig.codec.getDefaultExtension()

  val openFiles = scala.collection.mutable.Map[String, ActorRef]()

  val criticalError = new AtomicBoolean(false)

  def receive = {
    case (date: String, msg: String) =>
      write(date, msg.getBytes())
    case (date: String, msg: Array[Byte]) =>
      write(date, msg)
    case 'stop =>
      closeAll()
      sender ! 'stopped
      context.stop(self)
    case 'checkRolls =>
      checkFilesToRoll()
    case 'flush =>
      flushAll()
    case 'stopped =>
      ; //ignore, the file obj responded to the stop command
    case t:Terminated =>
         logger.error(t.getActor + " terminated")
    case m: Any =>
      logger.error("Coult not understand: " + m)
   
  }

  def flushAll() = {
    for ((date, fileObj) <- openFiles) fileObj ! 'flush

  }
  var checkI = 0

  def checkFilesToRoll() = {

    //usefull notification printing
    if (openFiles.size == 0) {
      if (checkI % 100 == 0) {
        logger.info("checkFilesToRoll for " + openFiles.size + " open files")
        checkI = 0
      } else {
        checkI = checkI + 1
      }
    } else
      logger.info("checkFilesToRoll for " + openFiles.size + " open files")

    val rollCheck = topicConfig.rollCheck
    for ((date, fileObj) <- openFiles) {
      val checkResult:Future[Boolean] = fileObj.ask(rollCheck)(new Timeout(10, TimeUnit.SECONDS)).mapTo[Boolean] 
      try{
	      if( Await.result(checkResult, Duration(10, TimeUnit.SECONDS)) ){
		      FileLogResource.stopActor(fileObj)
		      context.unwatch(fileObj)
		      openFiles -= date //remove this file from the open files list
	      }
      }catch{
        case e:Throwable =>
           logger.error(e.toString(), e)
      }
    }
  }

  def write(date: String, msg: Array[Byte]) = {
    //tried with existOrUpdate but with mutable maps it seems sometimes fail,
    //using the imperative steps here works
    if (openFiles.containsKey(date)) {
      openFiles(date) ! msg
    } else {
      val file = createFile(date)
      openFiles.put(date, file)
      file ! msg
    }
  }

  def createFile(date: String) = {
    val actor = FileObj(new File(baseDir, topic + "." + date + "." + System.currentTimeMillis() + extension + "_"), compressionPool, topicConfig, statusActor)
    context.watch(actor)
    actor
  }

  def closeAll() =
    for (fileObj <- openFiles.values) FileLogResource.stopActor(fileObj)

}

object FileObjUtil {
  val nArray = Array('\n'.toByte)
}

object FileObj {

  
  def apply(file: File, compression: CompressionPool, topicConfig: TopicConfig, statusActor: ActorRef) = {
    FileLogResource.system.actorOf(Props(new FileObj(file, compression, topicConfig, statusActor)), file.getName() + System.currentTimeMillis())
  }

}

/**
 * Handles the complete life cycle of creating a Compression OutputStream and closing releasing the stream from the CompressionPool.
 */
class FileObj(file: File, compression: CompressionPool, topicConfig: TopicConfig, statusActor: ActorRef) extends Actor {

   override val supervisorStrategy = AllForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
  }
  
  val logger = Logger.getLogger(classOf[FileObj])

  val fileOut = new FileOutputStream(file)
  val output = compression.create(fileOut, 1000, TimeUnit.MILLISECONDS)
  var modTs = System.currentTimeMillis()

  val walLog = new WALLog(new File(WALLog.fileName(file.getAbsolutePath())), false)

  val usenewLine = topicConfig.usenewLine
  val useBase64 = topicConfig.useBase64

  val base64 = new Base64()

  def lastModTime() = modTs

  override def postStop() = {
    try {
      close()
    } catch {
      case e: Throwable => logger.error(e.toString(), e)
    }
  }

  def receive = {
    case msg: Array[Byte] =>
      //WAL, respond, then write to log
      walLog << msg
      sender ! 'logged
      <<(msg)
    case 'flush =>
      output.flush()
    case check:DateSizeCheck =>
        //send back response true or false
      	sender !  check.shouldRoll(lastModTime(), size()) 
      	
  }

  var lines = 1L
  var linesPos = 0L
  var lastUpdateTS = System.currentTimeMillis()

  def <<(msg: => Array[Byte]) = {

    if (useBase64)
      output.write(base64.encode(msg))
    else
      output.write(msg)

    if (usenewLine)
      output.write(FileObjUtil.nArray)

    if (lines % 100000 == 0) {
      val linesDiff = lines - linesPos
      linesPos = lines
      val ts = System.currentTimeMillis()
      val timeDiff = ts - lastUpdateTS
      lastUpdateTS = ts

      logger.info("Have written " + linesDiff + " messages in " + timeDiff + "ms to " + file.getAbsolutePath())

    }

    lines = lines + 1

    if (statusActor != null)
      statusActor ! topicConfig.topic

    modTs = System.currentTimeMillis()
  }

  def size() = file.length()

  /**
   * Close and rename the file from name_ to name
   */
  def close() = {
    compression.closeAndRelease(output)
    file.renameTo(new File(file.getParentFile(), file.getName().init))
    walLog.destroy()
  }

}

object NonStatus extends Status {

  override def setCounter(s: String, c: Int) {}

  def getCounter() = 1

  override def getStatus(): STATUS = null

  override def getStatusMessage() = ""

  override def setStatus(s: STATUS, m: String) {
  }

  override def getStatusTimestamp() = 0L

}