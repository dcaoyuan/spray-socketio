#!/bin/sh

#export JAVA_HOME=/usr/lib/jvm/java-1.7.0-openjdk.x86_64
if [ -z "${JAVA_HOME}" ]
then
    echo "Please set environment JAVA_HOME";
    exit 1
fi

export JAVA=${JAVA_HOME}/bin/java
export FLAGS="-server -Dfile.encoding=UTF8 -XX:+UseNUMA -XX:+UseCondCardMark -XX:-UseBiasedLocking"
export HEAP="-Xms1024M -Xmx6000M -Xss1M -XX:MaxPermSize=128m"
export GC="-XX:+UseParallelGC"


cp="";
for f in ../libs/*.jar;
do cp=${f}":"${cp};
done;

dir_conf=../conf
benchserver_class_pgm=spray.contrib.socketio.examples.benchmark.SocketIOTestServer
benchserver_id_pgm=benchserver
benchserver_lock_file=.lock_benchserver
benchserver_conf=../conf/benchmark.conf
logback_conf=../conf/logback.xml

$JAVA $FLAGS $HEAP $GC -Dconfig.file=${benchserver_conf} -Dlogback.configurationFile=${logback_conf} -cp ${cp} ${benchserver_class_pgm} > ../logs/benchserver_rt.log &
benchserver_pid=$!
echo $benchserver_pid > ./${benchserver_lock_file}
echo "Started ${benchserver_id_pgm}, pid is $benchserver_pid"