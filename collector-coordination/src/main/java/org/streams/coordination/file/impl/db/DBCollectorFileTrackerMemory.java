package org.streams.coordination.file.impl.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.NoResultException;
import javax.persistence.Query;

import org.apache.log4j.Logger;
import org.jboss.netty.util.internal.ConcurrentHashMap;
import org.streams.commons.file.FileTrackingStatus;
import org.streams.commons.file.FileTrackingStatusKey;
import org.streams.coordination.file.AgentContact;
import org.streams.coordination.file.CollectorFileTrackerMemory;
import org.streams.coordination.file.LogTypeContact;

/**
 * Manages the database persistence for the FileTrackingStatus.<br/>
 * This object abstracts any calling object from the database by using the
 * FileTrackingStatus and the CollectorFileTrackerMemory interface.
 * 
 */
public class DBCollectorFileTrackerMemory implements CollectorFileTrackerMemory {

	private static final Logger LOG = Logger
			.getLogger(DBCollectorFileTrackerMemory.class);

	EntityManagerFactory entityManagerFactory;

	public DBCollectorFileTrackerMemory() {

	}

	public DBCollectorFileTrackerMemory(
			EntityManagerFactory entityManagerFactory) {
		super();
		this.entityManagerFactory = entityManagerFactory;
	}

	/**
	 * Gets a list of agent names from the persistence.
	 */
	public Collection<AgentContact> getAgents(int from, int max) {

		Collection<AgentContactEntity> entities = getEntityList(
				AgentContactEntity.class, "agentContact.list", from, max);
		Collection<AgentContact> agentContacts = new ArrayList<AgentContact>(
				entities.size());
		for (AgentContactEntity entity : entities) {
			agentContacts.add(entity.createAgentContact());
		}

		return agentContacts;
	}

	/**
	 * Delete's a FileTrackingStatus entry from the storage.
	 * 
	 * @param file
	 * @return true if done
	 */
	@Override
	public boolean delete(FileTrackingStatus file) {
		return delete(new FileTrackingStatusKey(file));
	}

	/**
	 * Delete the FileTrackingStatusEntity object
	 */
	@Override
	public boolean delete(FileTrackingStatusKey file) {

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		boolean ret = true;
		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.byAgentLogTypeFileUpdate");
			query.setParameter("agentName", file.getAgentName());
			query.setParameter("logType", file.getLogType());
			query.setParameter("fileName", file.getFileName());

			try {
				FileTrackingStatusEntity entity = (FileTrackingStatusEntity) query
						.getSingleResult();

				entityManager.remove(entity);

			} catch (NoResultException noResultExcp) {
				// ignore if no result is found
				ret = false;
			}
		} finally {
			commitTx(entityManager);
			entityManager.close();
		}

		return ret;
	}

	/**
	 * Executes the named query and expects a list of string results
	 * 
	 * @param queryName
	 * @param from
	 * @param max
	 * @return will return an empty collection if no results found
	 */
	@SuppressWarnings("unchecked")
	private <T> Collection<T> getEntityList(Class<T> entity, String queryName,
			int from, int max) {
		Collection<T> ls = null;

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager.createNamedQuery(queryName);
			query.setFirstResult(from);
			query.setMaxResults(max);

			ls = query.getResultList();

			if (ls == null || ls.size() < 1) {
				// create empty list
				ls = new ArrayList<T>();
			}

		} finally {
			commitReadTx(entityManager);
			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return ls;

	}

	/**
	 * Gets the Files for an agent
	 * 
	 * @param logType
	 * @param from
	 * @param max
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Collection<String> getFilesByLogType(String logType, int from,
			int max) {

		Collection<String> logTypes = null;

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.byLogTypeReadOnly");
			query.setParameter("logType", logType);
			query.setFirstResult(from);
			query.setMaxResults(max);

			logTypes = query.getResultList();

			if (logTypes == null || logTypes.size() < 1) {
				// create empty list
				logTypes = new ArrayList<String>();
			}

		} finally {
			commitReadTx(entityManager);
			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return logTypes;
	}

	/**
	 * Gets the Files for an agent
	 * 
	 * @param agentName
	 * @param logType
	 * @param from
	 * @param max
	 * @return
	 */
	public Collection<FileTrackingStatus> getFilesByAgentLogType(
			String agentName, String logType, int from, int max) {
		return getFiles("fileTrackingStatus.byAgentLogTypeReadOnly", from, max,
				new String[] { "agentName", agentName }, new String[] {
						"logType", logType });
	}

	/**
	 * 
	 * @param queryStr
	 *            this must be a valid JPA query where string, from
	 *            FileTrackingStatusEntity f will be prefixed to this query
	 *            string.<br/>
	 *            e.g. giving queryString = "where agentName=='test'" will
	 *            return in a query with
	 *            "from FileTrackingStatusEntity f agentName=='test'".
	 * 
	 * @return
	 */
	public long getFileCountByQuery(String queryStr) {

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		long count;

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createQuery("SELECT count(*) from FileTrackingStatusEntity f WHERE "
							+ queryStr);

			count = (Long) query.getSingleResult();

		} finally {
			commitReadTx(entityManager);
			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return count;

	}

	/**
	 * 
	 * @param queryStr
	 *            this must be a valid JPA query where string, from
	 *            FileTrackingStatusEntity f will be prefixed to this query
	 *            string.<br/>
	 *            e.g. giving queryString = "where agentName=='test'" will
	 *            return in a query with
	 *            "from FileTrackingStatusEntity f agentName=='test'".
	 * @param from
	 * @param max
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Collection<FileTrackingStatus> getFilesByQuery(String queryStr,
			int from, int max) {

		Collection<FileTrackingStatus> statusColl = null;

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createQuery("from FileTrackingStatusEntity f WHERE "
							+ queryStr);
			query.setFirstResult(from);
			query.setMaxResults(max);

			statusColl = convert((List<FileTrackingStatusEntity>) query
					.getResultList());

		} finally {
			commitReadTx(entityManager);
			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}
		return statusColl;

	}

	/**
	 * Gets the Files for an agent
	 * 
	 * @param agentName
	 * @param from
	 * @param max
	 * @return
	 */
	public Collection<FileTrackingStatus> getFilesByAgent(String agentName,
			int from, int max) {
		return getFiles("fileTrackingStatus.byAgentNameReadOnly", from, max,
				new String[] { "agentName", agentName });
	}

	/**
	 * Get all file entries
	 * 
	 * @param from
	 * @param max
	 * @return
	 */
	public Collection<FileTrackingStatus> getFiles(int from, int max) {
		return getFiles("fileTrackingStatus.list", from, max);
	}

	/**
	 * Get all file entries
	 * 
	 * @param from
	 * @param max
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Set<FileTrackingStatusKey> getKeys(int from, int max) {
		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		Set<FileTrackingStatusKey> coll = null;

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.list");
			query.setFirstResult(from);
			query.setMaxResults(max);

			List<FileTrackingStatusEntity> entities = (List<FileTrackingStatusEntity>) query
					.getResultList();
			if (entities != null && entities.size() > 0) {
				coll = new LinkedHashSet<FileTrackingStatusKey>(entities.size());

				for (FileTrackingStatusEntity entity : entities) {
					coll.add(entity.createStatusKeyObject());
				}

			} else {
				coll = new LinkedHashSet<FileTrackingStatusKey>();
			}

		} finally {
			commitReadTx(entityManager);

			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return coll;

	}

	/**
	 * Helper method for list queries
	 * 
	 * @param queryName
	 * @param from
	 * @param max
	 * @param properties
	 *            an array of array of length 2 e.g. new String[] { new
	 *            String[]{propertyName, propertyValue} }
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Collection<FileTrackingStatus> getFiles(String queryName, int from,
			int max, String[]... properties) {

		Collection<FileTrackingStatus> statusColl = null;

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager.createNamedQuery(queryName);
			query.setFirstResult(from);
			query.setMaxResults(max);

			if (properties != null) {
				for (String[] pair : properties) {
					query.setParameter(pair[0], pair[1]);
				}
			}

			statusColl = convert((List<FileTrackingStatusEntity>) query
					.getResultList());

		} finally {

			commitReadTx(entityManager);

			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}
		return statusColl;

	}

	/**
	 * Count the number of agents.
	 * 
	 * @return
	 */
	public long getLogTypeCount() {
		return getCount("fileTrackingStatus.countLogTypes", null, null);
	}

	/**
	 * Count the number of agents.
	 * 
	 * @return
	 */
	public long getAgentCount() {
		return getCount("fileTrackingStatus.countAgents", null, null);
	}

	/**
	 * Gets the total file count
	 * 
	 * @return
	 */
	public long getFileCount() {
		return getCount("fileTrackingStatus.count", null, null);
	}

	/**
	 * Gets the file count for an agent
	 * 
	 * @param agentName
	 * @return
	 */
	public long getFileCountByAgent(String agentName) {
		return getCount("fileTrackingStatus.countByAgentName", "agentName",
				agentName);
	}

	/**
	 * Helper method for retrieving count values
	 * 
	 * @param queryName
	 * @param property
	 * @param value
	 * @return
	 */
	private long getCount(String queryName, String property, String value) {

		Long count = null;
		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();
			Query query = entityManager.createNamedQuery(queryName);

			if (property != null)
				query.setParameter(property, value);

			count = (Long) query.getSingleResult();
		} finally {

			commitReadTx(entityManager);

			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return (count == null) ? 0L : count.longValue();
	}

	@SuppressWarnings("unchecked")
	@Override
	public Map<FileTrackingStatusKey, FileTrackingStatus> getStatus(
			Collection<FileTrackingStatusKey> keys) {

		LOG.info("Pre loading keys[" + keys.size()
				+ "] this might take some time");

		// instead of querying the data we just load it all.
		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		Map<FileTrackingStatusKey, FileTrackingStatus> map = new ConcurrentHashMap<FileTrackingStatusKey, FileTrackingStatus>();

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.list");
			query.setMaxResults(keys.size());

			List<FileTrackingStatusEntity> list = query.getResultList();
			
			int i = 0;
			for (FileTrackingStatusEntity entry : list) {
				FileTrackingStatusKey key = entry.createStatusKeyObject();
				if(i%1000 == 0){
					LOG.info("Loading keys at: " + i);
				}
				i++;
				
				if (keys.contains(key)) {
					map.put(key, entry.createStatusObject());
				}
			}

		} finally {

			commitReadTx(entityManager);

			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return map;

	}

	public Map<FileTrackingStatusKey, FileTrackingStatus> _getStatus(
			Collection<FileTrackingStatusKey> keys, int from, int end) {

		int size = keys.size();
		Map<FileTrackingStatusKey, FileTrackingStatus> valuesMap = new HashMap<FileTrackingStatusKey, FileTrackingStatus>(
				size);

		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {

			entityManager.getTransaction().begin();

			FileTrackingStatusKey[] keyArr = keys
					.toArray(new FileTrackingStatusKey[] {});
			System.out.println(from + " : " + end);
			for (int i = from; i < end; i++) {

				FileTrackingStatusKey key = keyArr[i];

				Query query = entityManager
						.createNamedQuery("fileTrackingStatus.byAgentFileNameLogTypeReadOnly");

				query.setParameter("agentName", key.getAgentName());
				query.setParameter("fileName", key.getFileName());
				query.setParameter("logType", key.getLogType());
				FileTrackingStatusEntity entity = null;

				try {
					entity = (FileTrackingStatusEntity) query.getSingleResult();
					valuesMap.put(key, entity.createStatusObject());

				} catch (NoResultException noResultExcp) {
					// ignore if no result is found
					continue;
				}

			}

		} finally {
			commitReadTx(entityManager);
			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return valuesMap;

	}

	/**
	 * Commits a write/update transaction
	 * 
	 * @param entityManager
	 */
	private void commitTx(EntityManager entityManager) {
		EntityTransaction tx = entityManager.getTransaction();
		try {

			if (tx.isActive()) {
				// test for rollback condition
				if (tx.getRollbackOnly()) {
					tx.rollback();
				} else {
					tx.commit();
				}
			}

		} finally {
			// if error in the commit state the transaction is marked for
			// rollback
			// and is still active.
			if (tx.isActive()) {
				tx.rollback();
			}
		}
	}

	/**
	 * Commits the transaction quietly. Is used for read operations where the
	 * success of a transaction commit is not important.
	 * 
	 * @param entityManager
	 */
	private void commitReadTx(EntityManager entityManager) {
		EntityTransaction tx = entityManager.getTransaction();
		try {

			if (tx.isActive()) {
				entityManager.getTransaction().commit();
			}

		} catch (Throwable t) {
			LOG.error(t.toString(), t);
		}
	}

	/**
	 * Gets a FileTrackingStatus from the persistent memory.<br/>
	 * This method will open a close a readonly transaction.<br/>
	 */
	@Override
	public FileTrackingStatus getStatus(String agentName, String logType,
			String fileName) {
		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		FileTrackingStatus status = null;

		try {
			entityManager.getTransaction().begin();
			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.byAgentFileNameLogTypeReadOnly");

			query.setParameter("agentName", agentName);
			query.setParameter("fileName", fileName);
			query.setParameter("logType", logType);

			try {
				FileTrackingStatusEntity entity = (FileTrackingStatusEntity) query
						.getSingleResult();
				status = entity.createStatusObject();

			} catch (NoResultException noResultExcp) {
				// ignore if no result is found
				status = null;
			}
		} finally {
			commitReadTx(entityManager);
			try {
				entityManager.close();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}

		return status;
	}

	@Override
	public void setStatus(Collection<FileTrackingStatus> statusList) {
		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();
			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.byAgentLogTypeFileUpdate");

			for (FileTrackingStatus status : statusList) {

				query.setParameter("agentName", status.getAgentName());
				query.setParameter("fileName", status.getFileName());
				query.setParameter("logType", status.getLogType());

				try {
					FileTrackingStatusEntity entity = (FileTrackingStatusEntity) query
							.getSingleResult();
					entity.update(status);
					entity.setLastModifiedTime(System.currentTimeMillis());

					entityManager.persist(entity);

				} catch (NoResultException noResultExcp) {
					// the entity does not exist yet
					FileTrackingStatusEntity entity = FileTrackingStatusEntity
							.createEntity(status);
					entity.setLastModifiedTime(System.currentTimeMillis());
					entityManager.persist(entity);
				}
			}

		} finally {
			commitTx(entityManager);
			entityManager.close();
		}
	}

	/**
	 * Creates a new entry if the FileTrackingStatus does not already exist,
	 * otherwise the existing entry is updated.<br/>
	 */
	@Override
	public void setStatus(FileTrackingStatus status) {
		EntityManager entityManager = entityManagerFactory
				.createEntityManager();

		try {
			entityManager.getTransaction().begin();

			Query query = entityManager
					.createNamedQuery("fileTrackingStatus.byAgentLogTypeFileUpdate");
			query.setParameter("agentName", status.getAgentName());
			query.setParameter("fileName", status.getFileName());
			query.setParameter("logType", status.getLogType());

			try {
				FileTrackingStatusEntity entity = (FileTrackingStatusEntity) query
						.getSingleResult();
				entity.update(status);
				entity.setLastModifiedTime(System.currentTimeMillis());

				entityManager.persist(entity);

			} catch (NoResultException noResultExcp) {
				// the entity does not exist yet
				FileTrackingStatusEntity entity = FileTrackingStatusEntity
						.createEntity(status);
				entity.setLastModifiedTime(System.currentTimeMillis());
				entityManager.persist(entity);
			}

			// persist log type contact
			LogTypeContactEntity logTypeEntity = entityManager.find(
					LogTypeContactEntity.class, status.getLogType());
			if (logTypeEntity == null) {
				entityManager.persist(new LogTypeContactEntity(status));
			} else {
				logTypeEntity.update(status);
				entityManager.persist(logTypeEntity);
			}

			// persist agent contact
			AgentContactEntity agentContactEntity = entityManager.find(
					AgentContactEntity.class, status.getAgentName());
			if (agentContactEntity == null) {
				entityManager.persist(new AgentContactEntity(status));
			} else {
				agentContactEntity.update(status);
				entityManager.persist(agentContactEntity);
			}

		} finally {
			commitTx(entityManager);
			entityManager.close();
		}
	}

	public EntityManagerFactory getEntityManagerFactory() {
		return entityManagerFactory;
	}

	public void setEntityManagerFactory(
			EntityManagerFactory entityManagerFactory) {
		this.entityManagerFactory = entityManagerFactory;
	}

	/**
	 * Converts the FileTrackingStatusEntity list to FileTrackingStatus.
	 * 
	 * @param statusEntityList
	 * @return
	 */
	private Collection<FileTrackingStatus> convert(
			List<FileTrackingStatusEntity> statusEntityList) {
		Collection<FileTrackingStatus> statusColl;

		if (statusEntityList == null || statusEntityList.size() < 1) {
			// create empty list
			statusColl = new ArrayList<FileTrackingStatus>();
		} else {
			// transform each FileTackingStatusEntity into
			// FileTrackingStatus object
			int len = statusEntityList.size();
			statusColl = new ArrayList<FileTrackingStatus>(len);
			for (FileTrackingStatusEntity entity : statusEntityList) {
				statusColl.add(entity.createStatusObject());
			}
		}

		return statusColl;
	}

	/**
	 * 
	 */
	@Override
	public Collection<LogTypeContact> getLogTypes(int from, int max) {

		Collection<LogTypeContactEntity> entities = getEntityList(
				LogTypeContactEntity.class, "logTypeContact.list", from, max);
		Collection<LogTypeContact> logTypeContacts = new ArrayList<LogTypeContact>(
				entities.size());
		for (LogTypeContactEntity entity : entities) {
			logTypeContacts.add(entity.createLogTypeContact());
		}

		return logTypeContacts;
	}

	@Override
	public FileTrackingStatus getStatus(FileTrackingStatusKey key) {
		return getStatus(key.getAgentName(), key.getLogType(),
				key.getFileName());
	}

}
