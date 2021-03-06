package org.streams.agent.file.impl.db;

import java.io.Serializable;
import java.util.Date;

import org.streams.agent.file.FileTrackingStatus;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.QueryHint;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.*;

/**
 * A separate entity class is used to store the actual FileTrackingStatus, this
 * means that the rest of the agent code is not infected with db code in an
 * environment where speed and lightness is of essence.
 * <p/>
 * The DBFileTrackerMemoryImpl is responsible for translating the
 * FiletrackingStatus from and to its Entity.
 */
@Entity
@Table(name = "file_tracking_status", uniqueConstraints = { @UniqueConstraint(columnNames = { "path" }) })
@NamedQueries(value = {
		@NamedQuery(name = "fileTrackingStatus.byStatusReady", query = "from FileTrackingStatusEntity f where f.status='READY' ORDER BY f.fileDate DESC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.byStatusReadyOrderAsc", query = "from FileTrackingStatusEntity f where f.status='READY' ORDER BY f.fileDate ASC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.byStatus", query = "from FileTrackingStatusEntity f where f.status=:status ORDER BY f.fileDate DESC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.byStatusOrderAsc", query = "from FileTrackingStatusEntity f where f.status=:status ORDER BY f.fileDate ASC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.list", query = "from FileTrackingStatusEntity f ORDER BY f.fileDate DESC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.listOrderAsc", query = "from FileTrackingStatusEntity f ORDER BY f.fileDate ASC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.byPathUpdate", query = "from FileTrackingStatusEntity f where f.path=:path ORDER BY f.fileDate DESC"),
		@NamedQuery(name = "fileTrackingStatus.byPath", query = "from FileTrackingStatusEntity f where f.path=:path ORDER BY f.fileDate DESC", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.countByStatus", query = "select COUNT(*) as count from FileTrackingStatusEntity f WHERE f.status=:status", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }),
		@NamedQuery(name = "fileTrackingStatus.count", query = "select COUNT(*) as count from FileTrackingStatusEntity f", hints = { @QueryHint(name = "org.hibernate.readOnly", value = "true") }) 
	})
public class FileTrackingStatusEntity implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", unique = true, nullable = false)
	@Basic(fetch = FetchType.EAGER)
	Long id;
	
	long lastModificationTime = 0L;
	long fileSize = 0L;

	String path;
	String status;
	int linePointer = 0;
	long filePointer = 0L;
	String logType;

	Date fileDate;
	Date sentDate;
	
	long parkTime = 0L;
	
	public FileTrackingStatusEntity(){}
	
	
	public FileTrackingStatusEntity(long lastModificationTime, long fileSize,
			String path, String status, int linePointer, long filePointer,
			String logType, Date fileDate, Date sentDate) {
		super();
		this.lastModificationTime = lastModificationTime;
		this.fileSize = fileSize;
		this.path = path;
		this.status = status;
		this.linePointer = linePointer;
		this.filePointer = filePointer;
		this.logType = logType;
		this.fileDate = fileDate;
		this.sentDate = sentDate;
	}


	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	@Column(name = "last_modification_time", nullable = false)
	public long getLastModificationTime() {
		return lastModificationTime;
	}

	public void setLastModificationTime(long lastModificationTime) {
		this.lastModificationTime = lastModificationTime;
	}

	@Column(name = "file_size", nullable = false)
	public long getFileSize() {
		return fileSize;
	}

	public void setFileSize(long fileSize) {
		this.fileSize = fileSize;
	}

	@Column(name = "path", updatable = false, nullable = false)
	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	@Column(name = "status", nullable = false)
	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	@Column(name = "line_pointer", nullable = false)
	public int getLinePointer() {
		return linePointer;
	}

	public void setLinePointer(int linePointer) {
		this.linePointer = linePointer;
	}

	@Column(name = "file_pointer", nullable = false)
	public long getFilePointer() {
		return filePointer;
	}

	public void setFilePointer(long filePointer) {
		this.filePointer = filePointer;
	}

	@Column(name = "log_type", nullable = false)
	public String getLogType() {
		return logType;
	}

	public void setLogType(String logType) {
		this.logType = logType;
	}


	public Date getFileDate() {
		return fileDate;
	}

	@Column(name = "file_date", nullable = true)
	public void setFileDate(Date fileDate) {
		this.fileDate = fileDate;
	}

	public Date getSentDate() {
		return sentDate;
	}


	@Column(name = "sent_date", nullable = true)
	public void setSentDate(Date sentDate) {
		this.sentDate = sentDate;
	}
	
	/**
	 * Creates a FileTrackingStatusEntity instance without an id value
	 * @param fileTrackingStatus
	 * @return
	 */
	public static FileTrackingStatusEntity createEntity(FileTrackingStatus fileTrackingStatus){
		
		FileTrackingStatusEntity entity = new FileTrackingStatusEntity(fileTrackingStatus.getLastModificationTime(),
				fileTrackingStatus.getFileSize(),
				fileTrackingStatus.getPath(),
				fileTrackingStatus.getStatus().toString().toUpperCase(),
				fileTrackingStatus.getLinePointer(),
				fileTrackingStatus.getFilePointer(),
				fileTrackingStatus.getLogType().toLowerCase(),
				fileTrackingStatus.getFileDate(),
				fileTrackingStatus.getSentDate());
		entity.setParkTime(fileTrackingStatus.getParkTime());
		return entity;
		
	}
	
	/**
	 * Updates the internal state of the entity wit hthe FileTrackingStatus values.
	 * @param status
	 */
	public void update(FileTrackingStatus status){
		
		setFilePointer(status.getFilePointer());
		setLastModificationTime(status.getLastModificationTime());
		setFileSize(status.getFileSize());
		setLinePointer(status.getLinePointer());
		setLogType(status.getLogType());
		setPath(status.getPath());
		setStatus(status.getStatus().toString().toUpperCase());
		setFileDate(status.getFileDate());
		setSentDate(status.getSentDate());
		setParkTime(status.getParkTime());
	}
	
	/**
	 * Creates a FileTrackingStatus instance
	 * 
	 * @return
	 */
	public FileTrackingStatus createStatusObject() {

		FileTrackingStatus status = new FileTrackingStatus(getLastModificationTime(), getFileSize(),
				getPath(), FileTrackingStatus.STATUS.valueOf(getStatus()),
				getLinePointer(), getFilePointer(), getLogType().toLowerCase(),
				getFileDate(), getSentDate());
		status.setParkTime(getParkTime());
		return status;
	}


	public long getParkTime() {
		return parkTime;
	}


	public void setParkTime(long parkTime) {
		this.parkTime = parkTime;
	}

}
