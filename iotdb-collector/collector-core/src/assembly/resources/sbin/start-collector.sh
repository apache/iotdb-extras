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

source "$(dirname "$0")/common.sh"

# iotdb collector server runs on foreground by default
foreground="yes"

if [ $# -ne 0 ]; then
  echo "All parameters are $*"
fi

while true; do
    case "$1" in
        -c)
            COLLECTOR_CONF="$2"
            shift 2
            ;;
        -p)
            pidfile="$2"
            shift 2
        ;;
        -f)
            foreground="yes"
            shift
        ;;
        -d)
            foreground=""
            shift
        ;;
        -g)
            PRINT_GC="yes"
            shift
        ;;
        -h)
            echo "Usage: $0 [-v] [-f] [-d] [-h] [-p pidfile] [-c configFolder] [-H HeapDumpPath] [-E JvmErrorFile] [printgc]"
            exit 0
        ;;
        -v)
            SHOW_VERSION="yes"
            break
        ;;
        --)
            shift
            #all others are args to the program
            PARAMS=$*
            break
        ;;
        "")
            #if we do not use getopt, we then have to process the case that there is no argument.
            #in some systems, when there is no argument, shift command may throw error, so we skip directly
            #all others are args to the program
            PARAMS=$*
            break
        ;;
        *)
            echo "Error parsing arguments! Unknown argument \"$1\"" >&2
            exit 1
        ;;
    esac
done

if [ "$(id -u)" -ne 0 ]; then
  echo "Notice: in some systems, DataNode must run in sudo mode to write data. The process may fail."
fi

#check_all_variables is in common.sh
check_all_variables

#check_collector_port_usages is in common.sh
check_collector_port_usages

CLASSPATH=""
for f in "${COLLECTOR_HOME}"/lib/*.jar; do
  CLASSPATH=${CLASSPATH}":"$f
done

classname=org.apache.iotdb.collector.Application

if [ "x$SHOW_VERSION" != "x" ]; then
    COLLECTOR_LOG_CONFIG="${COLLECTOR_CONF}/logback.xml"
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
    exit 0
fi

echo ---------------------
echo "Starting IoTDB Collector"
echo ---------------------

#init_env is in common.sh
init_env

# check whether we can enable heap dump when oom
if [ "x$COLLECTOR_ALLOW_HEAP_DUMP" == "xtrue" ]; then
  COLLECTOR_JVM_OPTS="$COLLECTOR_JVM_OPTS $COLLECTOR_HEAP_DUMP_COMMAND"
fi

PARAMS="-s $PARAMS"

classname=org.apache.iotdb.collector.Application

launch_service() {
  	class="$1"
    collector_parms="-Dlogback.configurationFile=${COLLECTOR_LOG_CONFIG}"
  	collector_parms="$collector_parms -DCOLLECTOR_HOME=${COLLECTOR_HOME}"
  	collector_parms="$collector_parms -DCOLLECTOR_DATA_HOME=${COLLECTOR_DATA_HOME}"
  	collector_parms="$collector_parms -DTSFILE_HOME=${COLLECTOR_HOME}"
  	collector_parms="$collector_parms -DCOLLECTOR_CONF=${COLLECTOR_CONF}"
  	collector_parms="$collector_parms -DTSFILE_CONF=${COLLECTOR_CONF}"
  	collector_parms="$collector_parms -Dname=collector\.IoTDB"
  	collector_parms="$collector_parms -DCOLLECTOR_LOG_DIR=${COLLECTOR_LOG_DIR}"
  	collector_parms="$collector_parms -DOFF_HEAP_MEMORY=${OFF_HEAP_MEMORY}"

    if [ "x$pidfile" != "x" ]; then
       collector_parms="collector_parms -Dcollector-pidfile=$pidfile"
    fi

    if [ "x$foreground" == "xyes" ]; then
        collector_parms="$collector_parms -Dcollector-foreground=yes"
        if [ "x$JVM_ON_OUT_OF_MEMORY_ERROR_OPT" != "x" ]; then
          [ ! -z "$pidfile" ] && printf "%d" $! > "$pidfile"
            exec $NUMACTL "$JAVA" $JVM_OPTS "$JVM_ON_OUT_OF_MEMORY_ERROR_OPT" $illegal_access_params $collector_parms $COLLECTOR_JMX_OPTS -cp "$CLASSPATH" $COLLECTOR_JVM_OPTS "$class" $PARAMS
        else
            [ ! -z "$pidfile" ] && printf "%d" $! > "$pidfile"
            exec $NUMACTL "$JAVA" $JVM_OPTS $illegal_access_params $collector_parms $COLLECTOR_JMX_OPTS -cp "$CLASSPATH" $COLLECTOR_JVM_OPTS "$class" $PARAMS
        fi
    # Startup IoTDB, background it, and write the pid.
    else
        if [ "x$JVM_ON_OUT_OF_MEMORY_ERROR_OPT" != "x" ]; then
              exec $NUMACTL "$JAVA" $JVM_OPTS "$JVM_ON_OUT_OF_MEMORY_ERROR_OPT" $illegal_access_params $collector_parms $COLLECTOR_JMX_OPTS -cp "$CLASSPATH" $COLLECTOR_JVM_OPTS "$class" $PARAMS 2>&1 > /dev/null  <&- &
              [ ! -z "$pidfile" ] && printf "%d" $! > "$pidfile"
              true
        else
              exec $NUMACTL "$JAVA" $JVM_OPTS $illegal_access_params $collector_parms $COLLECTOR_JMX_OPTS -cp "$CLASSPATH" $COLLECTOR_JVM_OPTS "$class" $PARAMS 2>&1 > /dev/null <&- &
              [ ! -z "$pidfile" ] && printf "%d" $! > "$pidfile"
              true
        fi
    fi

    return $?
}

# check whether tool 'lsof' exists
check_tool_env() {
  if  ! type lsof > /dev/null 2>&1 ; then
    echo ""
    echo " Warning: No tool 'lsof', Please install it."
    echo " Note: Some checking function need 'lsof'."
    echo ""
    return 1
  else
    return 0
  fi
}

# convert path to real full-path.
# e.g., /a/b/c/.. will return /a/b
# If path has been deleted, return ""
get_real_path() {
  local path=$1
  local real_path=""
  cd $path > /dev/null 2>&1
  if [ $? -eq 0 ] ; then
    real_path=$(pwd -P)
    cd -  > /dev/null 2>&1
  fi
  echo "${real_path}"
}

# check whether same directory's IoTDB node process has been running
check_running_process() {
  check_tool_env

  PIDS=$(ps ax | grep "$classname" | grep java | grep DCOLLECTOR_HOME | grep -v grep | awk '{print $1}')
  for pid in ${PIDS}
  do
    run_conf_path=""
    # find the abstract path of the process
    run_cwd=$(lsof -p $pid 2>/dev/null | awk '$4~/cwd/ {print $NF}')
    # find "-DCOLLECTOR_HOME=XXX" from the process command
    run_home_path=$(ps -fp $pid | sed "s/ /\n/g" | sed -n "s/-DCOLLECTOR_HOME=//p")
    run_home_path=$(get_real_path "${run_cwd}/${run_home_path}")

    #if dir ${run_home_path} has been deleted
    if [ "${run_home_path}" == "" ]; then
      continue
    fi

    current_home_path=$(get_real_path ${COLLECTOR_HOME})
    if [ "${run_home_path}" == "${current_home_path}" ]; then
      echo ""
      echo " Found running IoTDB Collector (PID=$pid)."  >&2
      echo " Can not run duplicated IoTDB Collector!"  >&2
      echo " Exit..."  >&2
      echo ""
      exit 1
    fi
  done
}

check_tool_env
# If needed tool is ready, check whether same directory's IoTDB node is running
if [ $? -eq 0 ]; then
  check_running_process
fi

# Start up the service
launch_service "$classname"

exit $?
