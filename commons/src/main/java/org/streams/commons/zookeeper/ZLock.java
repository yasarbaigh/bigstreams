package org.streams.commons.zookeeper;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.recipes.lock.WriteLock;

/**
 * 
 * Helper class to run a callable within a lock.
 * 
 */
public class ZLock {

	private static final Logger LOG = Logger.getLogger(ZLock.class);

	String hosts;
	long lockTimeout;
	String baseDir;

	private final AtomicBoolean init = new AtomicBoolean(false);

	public ZLock(String hosts, long lockTimeout) {
		super();
		this.hosts = hosts;
		this.baseDir = "/locks/";
		this.lockTimeout = lockTimeout;
	}

	public ZLock(String hosts, String baseDir, long lockTimeout) {
		super();
		this.hosts = hosts;
		this.baseDir = baseDir;
		this.lockTimeout = lockTimeout;
		if (!baseDir.endsWith("/"))
			baseDir = baseDir + "/";

	}

	/**
	 * Ensure that the path exists
	 * 
	 * @param zk
	 * @throws KeeperException
	 * @throws InterruptedException
	 */
	private final synchronized void init(ZooKeeper zk) throws KeeperException,
			InterruptedException {
		ZPathUtil.mkdirs(zk, baseDir);
		init.set(true);

	}

	/**
	 * Run the callable only if the lock can be obtained.
	 * 
	 * @param <T>
	 * @param lockId
	 * @param hosts
	 * @param lockTimeout
	 * @param c
	 * @return T return the object returned by the Callable
	 * @throws Exception
	 */
	public <T> T withLock(String lockId, Callable<T> c) throws Exception {
		ZooKeeper zk = ZConnection.getConnectedInstance(hosts, lockTimeout);

		if (!init.get()) {
			init(zk);
		}

		if (!lockId.startsWith("/")) {
			lockId = baseDir + lockId.substring(1, lockId.length());
		} else {
			lockId = baseDir + lockId;
		}

		// KeptLock lock = new KeptLock(zk, lockId, Ids.OPEN_ACL_UNSAFE);
		WriteLock writeLock = new WriteLock(zk, lockId, Ids.OPEN_ACL_UNSAFE);
		writeLock.setRetryDelay(100);

		boolean locked = false;
		try {
			locked = writeLock.lock();

			if (locked)
				return c.call();
			else {

				// if no lock go into retry logic
				int retries = 10;
				int retryCount = 0;

				while (!locked && retryCount++ < retries) {
					zk = ZConnection.getConnectedInstance(hosts, lockTimeout);
					writeLock = new WriteLock(zk, lockId, Ids.OPEN_ACL_UNSAFE);
					writeLock.setRetryDelay(100);

					LOG.info("LOCK Retry " + retryCount + " of " + retries);
					locked = writeLock.lock();
					Thread.sleep(500L);
				}

				if (locked) {
					return c.call();
				} else {
					LOG.info("Unable to attain lock for " + lockId);
				}

				throw new TimeoutException("Unable to attain lock " + lockId
						+ " using zookeeper " + hosts);
			}
		} finally {
			if (locked) {
				try {
					writeLock.unlock();
				} catch (Throwable iexp) {
					// ignore or eat it
					LOG.error(iexp, iexp);
				}
			}
		}

	}

	public String getHosts() {
		return hosts;
	}

	public void setHosts(String hosts) {
		this.hosts = hosts;
	}

	public long getLockTimeout() {
		return lockTimeout;
	}

	public void setLockTimeout(long lockTimeout) {
		this.lockTimeout = lockTimeout;
	}

}