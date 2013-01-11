package org.streams.streamslog.log.file

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import scala.annotation.tailrec

/**
 * Use a Preallocated buffer to write out data using a BufferMap.<br/>
 * This WAL will not flush to disk thus may loose data, but data is kept in the OS RAM, so
 * in case of a crash most of the data will not be lost. This is a performance choice.
 */
class WALLog(walFile: File, synced:Boolean=false) {

  walFile.createNewFile()
  val walOut = new RandomAccessFile(walFile, if(synced) "rws" else "rw")
  val walChannel = walOut.getChannel()
  val wBuf = walChannel.map(FileChannel.MapMode.READ_WRITE, 0, 1073741824);
  
  def <<(msg: => Array[Byte]) = {
    wBuf.putInt(msg.length)
    wBuf.put(msg);
  }
  
  def close() = {
    wBuf.force()
    walChannel.close()
    walOut.close();
  }
  
  def destroy() = {
    close()
    walFile.delete()
  }


  def read(){
      var i = 0
	  val size = wBuf.getInt(i)
	  i = i + 4
	  val msg = new Array[Byte](size)
	  wBuf.get(msg, i, size)
	  
  }
  
}

class ReplayWALLog(walFile:File) extends WALLog(walFile, true){

  override def <<(msg: => Array[Byte]) = {
    throw new RuntimeException("Replay log cannot be written to")
  }
  
  /**
   * Read the log message by message, the replay log is cleaned for each message, such that replay's are not repeatable
   */
  def replay(f: (Array[Byte]) => Unit, limit:Int=Integer.MAX_VALUE) = {
	
    @tailrec def _replay(f: (Array[Byte]) => Unit, acc:Int, limit:Int):Unit = {
        if(acc >= limit)
          return None
          
    	readRecord match {
	      case Some(msg:Array[Byte]) =>
	        f(msg) 
	         wBuf.flip()
	         wBuf.compact()
	         wBuf.force()
	         _replay(f, acc+1, limit)
	      case _ => None//ignore
	    }
    }
    
    _replay(f, 0, limit)
  }
  
  def readRecord():Option[Array[Byte]] = {
    
    val len = wBuf.getInt()
    if(len > 0){
      val arr = new Array[Byte](len)
      wBuf.get(arr)
      return Option(arr)
    }else{
      return None
    }
    
    
  }
}

object WALLog{
  
  def replayWalLog(file:File, destroy:Boolean, f:Array[Byte] => Unit) = {
    val log = new ReplayWALLog(file)
    try{
      log.replay(f)
    }finally{
      log.destroy
    }
        
  }
  
  def replayWalLog(file:File) = new ReplayWALLog(file)
  def walLog(file:File) = new WALLog(file)
  
  def fileName(name:String):String = {
    return name + "-wal"
  }
  
}
