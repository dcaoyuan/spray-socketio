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
benchclient_class_pgm=spray.contrib.socketio.examples.benchmark.SocketIOLoadTester
benchclient_id_pgm=benchclient
benchclient_lock_file=.lock_benchclient
benchclient_conf=../conf/benchmark.conf
logback_conf=../conf/logback.xml

$JAVA $FLAGS $HEAP $GC -Dconfig.file=${benchclient_conf} -Dlogback.configurationFile=${logback_conf} -cp ${cp} ${benchclient_class_pgm} > ../logs/benchclient_rt.log &
benchclient_pid=$!
echo $benchclient_pid > ./${benchclient_lock_file}
echo "Started ${benchclient_id_pgm}, pid is $benchclient_pid"