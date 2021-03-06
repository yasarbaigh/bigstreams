package org.streams.agent.mon.impl;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.streams.agent.file.FileTrackerMemory;
import org.streams.agent.file.FileTrackingStatus;

/**
 * 
 * This Callable is meant to run in the background every N period of time, and
 * delete any FileTrackingStatus objects with status==DONE older than N days.<br/>
 * The lastModifiedTime field is used.<br/>
 * <p/>
 * The query to find files to delete is:<br/>
 * status='DONE' and lastModificationTime < historyTimeLimit
 * <p/>
 * Limit to number of files deleted at a time is 1000.
 */
public class FileStatusCleanoutManager implements Callable<Integer>, Runnable {

	private static final Logger LOG = Logger
			.getLogger(FileStatusCleanoutManager.class);

	private FileTrackerMemory memory;

	private long historyTimeLimit;

	AtomicBoolean isClosed = new AtomicBoolean(false);

	/**
	 * 
	 * @param memory
	 * @param historyTimeLimit
	 *            any DONE file with a lastModificationTime older than the
	 *            historyTimeLimit will be removed.
	 */
	public FileStatusCleanoutManager(FileTrackerMemory memory,
			long historyTimeLimit) {
		this.memory = memory;
		this.historyTimeLimit = historyTimeLimit;
	}

	public void run() {
		try {
			call();
		} catch (InterruptedException exp) {
			Thread.interrupted();
		} catch (Exception exc) {
			RuntimeException tre = new RuntimeException(exc.toString(), exc);
			throw tre;
		}
	}

	/**
	 * Deletes from storage i.e. the database any file with status='DONE' and
	 * lastModificationTime < historyTimeLimit
	 * 
	 * @return returns the number of files removed
	 */
	@Override
	public Integer call() throws Exception {

		// find the files by status=DONE and lastModificationTime <
		// historyTimeLimit.
		// a maximum of 1000 files will be done.
		long currentTime = System.currentTimeMillis();

		Collection<FileTrackingStatus> list = memory.getFiles(
				"status='DONE' and ( " + currentTime
						+ " - lastModificationTime ) > " + historyTimeLimit, 0,
				1000);

		int counter = 0;

		if (list == null || list.size() < 1) {
			LOG.debug("No files to cleanup");
		} else {

			LOG.info("Starting to clean " + list.size() + " files");

			// for each file found do delete
			for (FileTrackingStatus file : list) {

				if (isClosed.get()) {
					return counter;
				}

				LOG.info("Removing out " + file.getPath());
				File diskFile = new File(file.getPath());
				if (diskFile.exists()) {
					LOG.warn(diskFile.getAbsolutePath()
							+ " is DONE and listed for historic cleanup but still exists on the disk, this file will not be removed from the internal storage");
				} else {
					memory.delete(diskFile);

					counter++;
				}
			}

			LOG.debug("Removed " + counter + " files ");
		}

		// return the number of files deleted
		return counter;

	}

	public void close() {
		isClosed.set(true);
	}

}
