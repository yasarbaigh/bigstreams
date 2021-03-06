package org.streams.collector.conf;

import org.apache.hadoop.io.compress.GzipCodec;
import org.streams.collector.server.CollectorServer;
import org.streams.collector.write.impl.DateHourFileNameExtractor;


public interface CollectorProperties {

	enum WRITER{
		VERSION("collector.version", "UNKOWN"),
		
		DISK_FULL_KB_ACTIVATION("diskfull.freespacekb.activation", 1024L),
		DISK_FULL_ACTION("diskfull.action", "ALERT"),
		DISK_FULL_FREQUENCY("diskfull.check.frequency", 10000L),
		
		LOG_NAME_EXTRACTOR("writer.logname.extractor", DateHourFileNameExtractor.class.getCanonicalName()),
		LOG_NAME_KEYS("writer.logname.keys", "logType"),
		BASE_DIR("writer.basedir", "/var/log/streams"),
		LOG_SIZE_MB("writer.logsize", 128L),
		LOG_ROTATE_TIME("writer.logrotate.time", 5*60*1000L),
		LOG_ROTATE_CHECK_PERIOD("writer.logrotate.check.period", 1000L),
		
		//roll the log when no data has been received during this time period.
		//this allows files to be closed when not needed
		LOG_ROTATE_INACTIVE_TIME("writer.logrotate.inactivetime", 1000L),
		
		
		LOG_COMPRESSION_CODEC("writer.compressions.codec", GzipCodec.class.getCanonicalName()),
		LOG_COMPRESS_OUTPUT("writer.compress.output", true),
		
		OPEN_FILE_LIMIT("openfile.limit", 30000L),
		
		REDIS_HOST("redis.host", "localhost"),
		
		COORDINATION_GROUP("coordination.group", "default"),
		BLOCKED_IPS("blocked.ips", ""),
		
		HEARTBEAT_FREQUENCY("heartbeat.frequency", 1000L),
		
		ZOOTIMEOUT("zoo.timeout", 80000L),

		ZSTORE_TIMEOUT_DELAY("zstore.timeout.delay", 10000L),
		ZSTORE_TIMEOUT_CHECK("zstore.timeout.check",  86400000L), //1 days
		ZSTORE_DATA_TIMEOUT("zstore.timeout.seconds", 5184000), // 60 days
		
		ORPHANED_LOG_CHECK_FREQUENCY("orphaned.check.frequency",  1200000L), //20 minutes
		ORPHANED_FILE_LOWER_MODE("orphaned.filelowermod", 3600000L), //1 hour
		
		COORDINATION_HOST ("coordination.host", "localhost"),
		COLLECTOR_PORT ("collector.port", 8210),
		COLLECTOR_MON_PORT ("collector.mon.port", 8080),
		
		PING_PORT("ping.port", 8082),

		COLLECTOR_WORKER_THREAD_POOL("collector.worker.thread.pool", CollectorServer.THREAD_POOLS.CACHED.toString()),
		COLLECTOR_WORKERBOSS_THREAD_POOL("collector.workerboss.thread.pool", CollectorServer.THREAD_POOLS.CACHED.toString()),
		//only used if thread pool is type FIXED or MEMORY
		COLLECTOR_WORKER_THREAD_COUNT("collector.worker.thread.count", 100),
		//only used if thread pool is type FIXED
		COLLECTOR_WORKERBOSS_THREAD_COUNT("collector.workerboss.thread.count", 2),
		
		//only used if thread pool is type MEMORY default 1 meg
		COLLECTOR_CHANNEL_MAX_MEMORY_SIZE("collector.worker.channel.memorysize", 1048576L),
		//only used if thread pool is type MEMORY default 1 gig
		COLLECTOR_TOTAL_MEMORY_SIZE("collector.worker.total.memorysize", 1073741824L),
		
		METRIC_REFRESH_PERIOD("metric.refresh.period", 10000L),
		
		
		COLLECTOR_COMPRESSOR_POOLSIZE("collector.compressor.poolsize", 100),
		COLLECTOR_DECOMPRESSOR_POOLSIZE("collector.decompressor.poolsize", 100),
		
		COLLECTOR_CONNECTION_READ_TIMEOUT("collector.read.timeout", 10000L),
		COLLECTOR_CONNECTION_WRITE_TIMEOUT("collector.write.timeout", 10000L);
			
		
		String name;
		Object defaultValue;
		
		WRITER(String name, Object defaultValue){this.name = name; this.defaultValue = defaultValue;};
		
		public String toString(){return name;}

		public Object getDefaultValue() {
			return defaultValue;
		}
	
	}
	

	enum WEB{
		
		VELOCITY_TEMPLATE_DIR("velocity.template.dir", "/opt/streams-collector/web/templates"),
		VELOCITY_LOG_FILE("velocity.log.dir", "/opt/streams-collector/logs/streams-collector.log"),
		//These are threads that the ui components can use to make parallel requests to other clients
		//e.g. to multiple agents
		UI_AUX_THREADS("ui.aux.threads", 10),
		UI_STATUS_UPDATE("ui.status.update", 1000L);
		
		String name;
		Object defaultValue;
		
		WEB(String name, Object defaultValue){this.name = name; this.defaultValue = defaultValue;};
		
		public String toString(){return name;}

		public Object getDefaultValue() {
			return defaultValue;
		}
	

	}
	
}
