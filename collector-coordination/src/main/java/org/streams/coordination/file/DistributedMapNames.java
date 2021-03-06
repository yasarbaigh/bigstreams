package org.streams.coordination.file;

/**
 * 
 * 
 * Currently only hazelcast is used for the distributed maps, this class
 * abstracts the names into an independent interface.
 * <ul>
 *   <li>FILE_TRACKER_MAP: for storing the FileTrackStatus information.</li>
 *   <li>LOCK_MEMORY_LOCKS_MAP: for in memory distributed locks.</li>
 * </ul>
 */
public interface DistributedMapNames {

	enum SET {
		
	}
	
	enum MAP {
		FILE_TRACKER_MAP("COORDINATION_FILE_TRACKER_MEMORY_MAP"),
		LOCK_MEMORY_LOCKS_MAP("LOCK_MEMORY_LOCKS_MAP"),
		FILE_TRACKER_HISTORY_MAP("FILE_TRACKER_HISTORY_MAP"),
		FILE_TRACKER_HISTORY_LATEST_MAP("FILE_TRACKER_HISTORY_LATEST_MAP"),
		AGENT_NAMES("AGENT_NAMES_MAP"),
		LOG_TYPES("LOG_TYPES_MAP");
		
		String key = null;

		MAP(String key) {
			this.key = key;
		}

		public String toString() {
			return key;
		}
	}

}
