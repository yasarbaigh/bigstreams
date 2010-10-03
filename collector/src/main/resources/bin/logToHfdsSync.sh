#!/usr/bin/env bash

# This script will sync the files generated by the chukwa collector to hdfs only if the file doesn't exist on hdfs or the file size is smaller on hdfs.
# After a file has been uploaded to hdfs it will be moved to the synced folder

if [ -z $HADOOP_HOME ] ; then
 if [ -e /etc/profile.d/hadoop.sh ]; then
  source /etc/profile.d/hadoop.sh
 else
  echo "Please set the HADOOP_HOME variable"
  exit -1
 fi
fi


if [ -z $3 ]
then
 echo "Type <local_log_dir> <synced_dir> <hdfs_dir>"
 exit -1
fi

LOCAL_LOG_DIR=$1
SYNCED_DIR=$2
HDFS_DIR=$3

LOG_FILE_EXTENSION=".lzo"


for f in $( find $LOCAL_LOG_DIR -name "*[0-9][0-9][0-9][0-9]-[0-9][0-9]\-[0-9][0-9]\-[0-9][0-9].*?$LOG_FILE_EXTENSION" )
do

fileName=$( basename $f)
echo "fileName: $fileName"


uploadFileName="$HOSTNAME.$fileName"


logtype=$( echo "$fileName" | awk -F "." '{print $1}')

daydate_part=$( echo "$fileName" | awk -F '.' '{print $2}' )

#the daydate format is yyyy/MM/dd
daydate=$( echo "$daydate_part" | awk -F '-' '{print "year="$1"/month="$2"/day="$3}' )


#validate the daydate field. We do not know what the filename will be we can only assume
#if the file name is not in the format expected then we'll not get the correct daydate
if [[ ! "$daydate" =~ (year=[0-9][0-9][0-9][0-9]/month=[0-9][0-9]/day=[0-9][0-9]) ]]
then
   echo "File name incorrect"
   echo "The daydate format for file $fileName was $daydate"
   exit -1
fi


hour=$( echo "$daydate_part" | awk -F '-' '{print $4}' )


#validate hour
if [[ ! "$hour" =~ ([0-9][0-9]) ]]
then
  echo "File name incorrect"
  echo "The hour format is not correct for file $fileName was $hour"
fi



echo "Date $daydate Hour $hour LogType: $logType Upload Name $uploadFileName"

if ! $HADOOP_HOME/bin/hadoop fs -test -d "$HDFS_DIR/$logtype/$daydate/hour=$hour" ; then

    if ! $HADOOP_HOME/bin/hadoop fs -mkdir "$HDFS_DIR/$logtype/$daydate/hour=$hour" ; then
       echo "could not create directory $HDFS_DIR/$logtype/$daydate/hour=$hour"
       exit -1
    fi

fi


if ! $HADOOP_HOME/bin/hadoop fs -ls "$HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName" ; then

   if ! $HADOOP_HOME/bin/hadoop fs -put $f $HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName ; then
      echo " failed to upload $uploadFileName retrying once more"
      if ! $HADOOP_HOME/bin/hadoop fs -put $f $HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName ; then
         echo "Failed to upload $uploadFileName"
         exit -1
      else 
        mv $f $SYNCED_DIR/
      fi
   else
     mv $f $SYNCED_DIR/
   fi

else
 #compare file sizes
hdfsFileSize=$($HADOOP_HOME/bin/hadoop fs -du $HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName | awk '/hdfs/ { print $1 }' )
localFileSize=$(du -b $f | awk '{ print $1}')


 if [ $hdfsFileSize -lt $localFileSize ] ; then
   #overwrite the existing file on hdfs BY
   if ! $HADOOP_HOME/bin/hadoop fs -rm  $HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName ; then
     echo "Failed to remove file  $HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName"
     exit -1
   fi
   
   if ! $HADOOP_HOME/bin/hadoop fs -put $f $HDFS_DIR/$logtype/$daydate/hour=$hour/$uploadFileName ; then
      echo "Failed to upload $uploadFileName"
      exit -1
   else
      mv $f $SYNCED_DIR/
   fi
 else
   echo "File exists on hdfs file localsize $localFileSize hdfssize $hdfsFileSize"
   mv $f $SYNCED_DIR/
 fi 

fi


done
