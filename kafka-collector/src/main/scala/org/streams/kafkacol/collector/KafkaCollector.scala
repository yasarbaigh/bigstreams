package org.streams.kafkacol.collector

import java.io.File
import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions.asScalaBuffer
import org.apache.log4j.Logger
import org.streams.kafkacol.conf.CollectorConfig
import org.streams.kafkacol.conf.CollectorConfig.apply
import org.streams.streamslog.log.file.FileLogResource
import joptsimple.OptionParser
import org.mortbay.resource.FileResource
import org.streams.streamslog.log.file.FileLogResource
import com.codahale.metrics.health.HealthCheck
import com.codahale.metrics.health.HealthCheck
import org.I0Itec.zkclient.ZkClient
import kafka.utils.ZKStringSerializer

/**
 * Kafka Collector application
 */
object KafkaCollector{

  val logger = Logger.getLogger(getClass)
    
  val parser = new OptionParser(){
      accepts("config").withRequiredArg().ofType(classOf[File])
      .describedAs("configuration directory")
    };
       
  def main(args:Array[String]):Unit = {
    
    try{
    	val options = parser.parse(args:_*)
    	val configDir = options.valueOf("config").asInstanceOf[File]
    	
    	runApp(configDir)
    	
    }catch{
      case e => 
        	logger.error(e.toString(), e);
        	e.printStackTrace()
            parser.printHelpOn(System.out)
            System.exit(-1)
    }finally{
      
      Metrics.shutdown
      
    }

    System.exit(0)
  }
  
  def runApp(configDir:File) = {
    
    def replayLogFiles(collectorConf:CollectorConfig, fileLogResource:FileLogResource) = {
      
      if(collectorConf.replayWAL){
    	  val logReplayCheck = new LogFileReplayCheck(fileLogResource)
    	  //get a unique set of directories to which the topics are written
    	  val files = collectorConf.topicConfigs.foldLeft(Set[File]())({ (files, topicConfig) => files + topicConfig.baseDir })
    	  //for each search for replay files
    	  for(file <- files)
    	    logReplayCheck.check(file)
      }
    }
    
    val collectorConf = CollectorConfig(configDir)
    val execService = Executors.newCachedThreadPool()
    val fileLogResource = FileLogResource(collectorConf.topicMap, collectorConf.compressorCount)
    
    try{
      
      replayLogFiles(collectorConf, fileLogResource)
      
      val kafkaConsumer = new KafkaConsumer(execService, fileLogResource, collectorConf.retriesOnError)
      kafkaConsumer.consume(collectorConf)
      
      Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler(){
        val shutdown = new AtomicBoolean(false)
        override def uncaughtException(t:Thread, e:Throwable):Unit = {
          e.printStackTrace()
          
          if(shutdown.get())
            return;
           
          shutdown.set(true)
          
          fileLogResource.close
          execService.shutdown()
          FileLogResource.shutdown
          kafkaConsumer.criticalError.set(true)
          
        }
      })
      
      Runtime.getRuntime().addShutdownHook(new Thread(){
        override def run() = {
          fileLogResource.close
          execService.shutdownNow()
          FileLogResource.shutdown
          logger.info("bye")
        }
      })
      
      /**
       * We add in a health check
       * 
       */
      
      Metrics.register("health", new HealthCheck(){
        
        def check():HealthCheck.Result = {
          if(kafkaConsumer.criticalError.get())
             return HealthCheck.Result.unhealthy(kafkaConsumer.error.get());
          else if(FileLogResource.system.isTerminated)
             return HealthCheck.Result.unhealthy("[Critical] Local file writing actors have been terminated")
          else if(kafkaConsumer.nonCriticalError.get())
             return HealthCheck.Result.unhealthy("[Error] Cannot connect to zookeeper")
          else 
             return HealthCheck.Result.healthy();
        }
        
      });
      
      Metrics.startHttp(collectorConf.httpPort)
      
      logger.info("Consumption started, waiting for shutdown...")
      while(!(Thread.currentThread().isInterrupted() || kafkaConsumer.criticalError.get() || FileLogResource.system.isTerminated))
    	   Thread.sleep(1000L)
      	   
    }catch{
      case e:InterruptedException => logger.info("closing")
      case e:Throwable => logger.error(e.toString(), e)
    }finally{
      
      execService.shutdown()
      logger.info("waiting for shutdown")
      if(!execService.awaitTermination(5, TimeUnit.SECONDS)){
        logger.warn("forcing shutdown")
        for(th <- execService.shutdownNow())
          logger.warn("Forced shutdown for thread: " + th)
      }

      //this will cause the shutdown hook to run
      System.exit(0)
    }
    
    
  }
  
  def loadCollectorConf(configDir:File) = CollectorConfig(configDir)
  
  
}	