#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# find java in JAVA_HOME
if [ -n "$JAVA_HOME" ]; then
    for java in "$JAVA_HOME"/bin/amd64/java "$JAVA_HOME"/bin/java; do
        if [ -x "$java" ]; then
            JAVA="$java"
            break
        fi
    done
else
    JAVA=java
fi

if [ -z $JAVA ] ; then
    echo Unable to find java executable. Check JAVA_HOME and PATH environment variables.  > /dev/stderr
    exit 1;
fi

# Determine the sort of JVM we'll be running on.
java_ver_output=`"$JAVA" -version 2>&1`
jvmver=`echo "$java_ver_output" | grep '[openjdk|java] version' | awk -F'"' 'NR==1 {print $2}' | cut -d\- -f1`
JVM_VERSION=${jvmver%_*}
JVM_PATCH_VERSION=${jvmver#*_}
if [ "$JVM_VERSION" \< "1.8" ] ; then
    echo "IoTDB requires Java 8u92 or later."
    exit 1;
fi

if [ "$JVM_VERSION" \< "1.8" ] && [ "$JVM_PATCH_VERSION" -lt 92 ] ; then
    echo "IoTDB requires Java 8u92 or later."
    exit 1;
fi

version_arr=(${JVM_VERSION//./ })

illegal_access_params=""
#GC log path has to be defined here because it needs to access COLLECTOR_HOME
if [ "${version_arr[0]}" = "1" ] ; then
    # Java 8
    MAJOR_VERSION=${version_arr[1]}
    echo "$COLLECTOR_JMX_OPTS" | grep -q "^-[X]loggc"
    if [ "$?" = "1" ] ; then # [X] to prevent ccm from replacing this line
        # only add -Xlog:gc if it's not mentioned in jvm-server.options file
        mkdir -p ${COLLECTOR_HOME}/logs
        if [ "$#" -ge "1" -a "$1" == "printgc" ]; then
            COLLECTOR_JMX_OPTS="$COLLECTOR_JMX_OPTS -Xloggc:${COLLECTOR_HOME}/logs/gc.log -XX:+PrintGCDateStamps -XX:+PrintGCDetails -XX:+PrintGCApplicationStoppedTime -XX:+PrintPromotionFailure -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=10M"
            # For more detailed GC information, you can uncomment option below.
            # NOTE: more detailed GC information may bring larger GC log files.
            # COLLECTOR_JMX_OPTS="$COLLECTOR_JMX_OPTS -Xloggc:${COLLECTOR_HOME}/logs/gc.log -XX:+PrintGCDateStamps -XX:+PrintGCDetails -XX:+PrintGCApplicationStoppedTime -XX:+PrintPromotionFailure -XX:+UseGCLogFileRotation -XX:+PrintTenuringDistribution -XX:+PrintHeapAtGC -XX:+PrintReferenceGC -XX:+PrintSafepointStatistics -XX:PrintSafepointStatisticsCount=1 -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=100M"
        fi
    fi
else
    #JDK 11 and others
    MAJOR_VERSION=${version_arr[0]}
    # See description of https://bugs.openjdk.java.net/browse/JDK-8046148 for details about the syntax
    # The following is the equivalent to -XX:+PrintGCDetails -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=10M
    echo "$COLLECTOR_JMX_OPTS" | grep -q "^-[X]log:gc"
    if [ "$?" = "1" ] ; then # [X] to prevent ccm from replacing this line
        # only add -Xlog:gc if it's not mentioned in jvm-server.options file
        mkdir -p ${COLLECTOR_HOME}/logs
        if [ "$#" -ge "1" -a "$1" == "printgc" ]; then
            COLLECTOR_JMX_OPTS="$COLLECTOR_JMX_OPTS -Xlog:gc=info,heap*=info,age*=info,safepoint=info,promotion*=info:file=${COLLECTOR_HOME}/logs/gc.log:time,uptime,pid,tid,level:filecount=10,filesize=10485760"
            # For more detailed GC information, you can uncomment option below.
            # NOTE: more detailed GC information may bring larger GC log files.
            # COLLECTOR_JMX_OPTS="$COLLECTOR_JMX_OPTS -Xlog:gc*=debug,heap*=debug,age*=trace,metaspace*=info,safepoint*=debug,promotion*=info:file=${COLLECTOR_HOME}/logs/gc.log:time,uptime,pid,tid,level,tags:filecount=10,filesize=100M"
        fi
    fi
    # Add argLine for Java 11 and above, due to [JEP 396: Strongly Encapsulate JDK Internals by Default] (https://openjdk.java.net/jeps/396)
    illegal_access_params="$illegal_access_params --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
    illegal_access_params="$illegal_access_params --add-opens=java.base/java.lang=ALL-UNNAMED"
    illegal_access_params="$illegal_access_params --add-opens=java.base/java.util=ALL-UNNAMED"
    illegal_access_params="$illegal_access_params --add-opens=java.base/java.nio=ALL-UNNAMED"
    illegal_access_params="$illegal_access_params --add-opens=java.base/java.io=ALL-UNNAMED"
    illegal_access_params="$illegal_access_params --add-opens=java.base/java.net=ALL-UNNAMED"
fi