package org.streams.coordination.mon;

import org.streams.commons.status.Status;

/**
 * 
 * Status of the coordination service
 */
public interface CoordinationStatus extends Status{

	enum STATUS {
		SHUTDOWN, OK, UNKOWN_ERROR
	};
	

	/**
	 * 
	 * @return
	 */
	STATUS getStatus();
	
	/**
	 * 
	 * @return
	 */
	String getMsg();
	
	void setMsg(String msg);
	
	/**
	 * 
	 * @param status
	 */
	void setStatus(STATUS status, String msg);
	
	/**
	 * A list of counters can be maintained and set by any component.<br/>
	 * Increment a counter<br/>
	 * @param name
	 * @param value
	 */
	public void incCounter(String name, int value);
	/**
	 * Decrement a counter.
	 * @param name
	 * @param value
	 */
	public void decCounter(String name, int value);
	
	public int getCounter(String name);

}
