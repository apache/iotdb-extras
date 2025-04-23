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

# this function is for parsing the variables like "A=B" in  `start-server.sh -D A=B`
# The command just parse COLLECTOR-prefixed variables and ignore all other variables
check_env_variables() {
  string="$1"
  array=$(echo $string | tr '=' ' ')
  eval set -- "$array"
  case "$1" in
          COLLECTOR_INCLUDE)
               COLLECTOR_INCLUDE="$2"
          ;;
          COLLECTOR_HOME)
               COLLECTOR_HOME="$2"
          ;;
          COLLECTOR_DATA_HOME)
             COLLECTOR_DATA_HOME="$2"
          ;;
          COLLECTOR_CONF)
             COLLECTOR_CONF="$2"
          ;;
          COLLECTOR_LOG_DIR)
             COLLECTOR_LOG_DIR="$2"
          ;;
          COLLECTOR_LOG_CONFIG)
             COLLECTOR_LOG_CONFIG="$2"
          ;;
          COLLECTOR_CLI_CONF)
             COLLECTOR_CLI_CONF="$2"
          ;;
          *)
            #we can not process it, so that we return back.
            echo "$1=$2"
          ;;
  esac
  echo ""
}

check_all_variables() {
  if [ -z "${COLLECTOR_INCLUDE}" ]; then
    #do nothing
    :
  elif [ -r "$COLLECTOR_INCLUDE" ]; then
      . "$COLLECTOR_INCLUDE"
  fi

  if [ -z "${COLLECTOR_HOME}" ]; then
    export COLLECTOR_HOME="`dirname "$0"`/.."
  fi

  if [ -z "${COLLECTOR_DATA_HOME}" ]; then
    export COLLECTOR_DATA_HOME=${COLLECTOR_HOME}
  fi

  if [ -z "${COLLECTOR_CONF}" ]; then
    export COLLECTOR_CONF=${COLLECTOR_HOME}/conf
  fi

  if [ -z "${COLLECTOR_LOG_DIR}" ]; then
    export COLLECTOR_LOG_DIR=${COLLECTOR_HOME}/logs
  fi

  if [ -z "${COLLECTOR_LOG_CONFIG}" ]; then
    export COLLECTOR_LOG_CONFIG="${COLLECTOR_CONF}/logback.xml"
  fi
}

check_config_unique() {
  local key=$1
  local values=$2

  line_count=$(echo "$values" | wc -l)

  if [ "$line_count" -gt 1 ]; then
    echo "Error: Duplicate $key entries found"
    exit 1
  fi
}

check_collector_port_usages() {
  echo "Checking whether the ports are already occupied..."
  if [ "$(id -u)" -ne 0 ]; then
      echo "Warning: If you do not use sudo, the checking may not detect all the occupied ports."
  fi
  occupied=false
  if [ -f "$COLLECTOR_CONF/application.properties" ]; then
      api_service_port=$(sed '/^api_service_port=/!d;s/.*=//' "${COLLECTOR_CONF}"/application.properties | tr -d '\r')
  elif [ -f "$COLLECTOR_HOME/conf/application.properties" ]; then
      api_service_port=$(sed '/^api_service_port=/!d;s/.*=//' "${COLLECTOR_HOME}"/conf/application.properties | tr -d '\r')
  elif [ -f "$COLLECTOR_CONF/application.properties" ]; then
    api_service_port=$(sed '/^api_service_port=/!d;s/.*=//' "${COLLECTOR_CONF}"/application.properties | tr -d '\r')
  elif [ -f "$COLLECTOR_HOME/conf/application.properties" ]; then
    api_service_port=$(sed '/^api_service_port=/!d;s/.*=//' "${COLLECTOR_HOME}"/conf/application.properties | tr -d '\r')
  else
    echo "Warning: cannot find application.properties, check the default configuration"
  fi

  check_config_unique "api_service_port" "$api_service_port"

  api_service_port=${api_service_port:-17070}
  if type lsof >/dev/null 2>&1; then
    PID=$(lsof -t -i:"${api_service_port}" -sTCP:LISTEN)
    if [ -n "$PID" ]; then
      echo "The api_service_port" "$api_service_port" "is already occupied, PID:" "$PID"
      occupied=true
    fi
  elif type netstat >/dev/null 2>&1; then
    PID=$(netstat -anp 2>/dev/null | grep ":${api_service_port} " | grep ' LISTEN ' | awk '{print $NF}' | sed "s|/.*||g")
    if [ -n "$PID" ]; then
      echo "The api_service_port" "$api_service_port" "is already occupied, PID:" "$PID"
      occupied=true
    fi
  else
    echo " Error: No necessary tool to check whether given port is occupied, stop ports checking"
    echo " Please install 'lsof' or 'netstat'."
  fi
  if [ $occupied = true ]; then
    echo "Exit because there are occupied ports."
    exit 0
  fi
}

init_env() {
  if [ -f "$COLLECTOR_CONF/collector-env.sh" ]; then
      if [ "x$PRINT_GC" != "x" ]; then
        . "$COLLECTOR_CONF/collector-env.sh" "printgc"
      else
          . "$COLLECTOR_CONF/collector-env.sh"
      fi
  elif [ -f "${COLLECTOR_HOME}/sbin/collector-env.sh" ]; then
      if [ "x$PRINT_GC" != "x" ]; then
        . "${COLLECTOR_HOME}/sbin/collector-env.sh" "printgc"
      else
        . "${COLLECTOR_HOME}/sbin/collector-env.sh"
      fi
  else
      echo "Can't find $COLLECTOR_CONF/collector-env.sh"
  fi
}

get_iotdb_collector_include() {
  #reset $1 to $* for this command
  eval set -- "$1"
  VARS=""
  while true; do
      case "$1" in
          -D)
              VARS="$VARS $(checkEnvVariables $2)"
              shift 2
          ;;
          "")
            #if we do not use getopt, we then have to process the case that there is no argument.
            #in some systems, when there is no argument, shift command may throw error, so we skip directly
              break
          ;;
          *)
              VARS="$VARS $1"
              shift
          ;;
      esac
  done
  echo "$VARS"
}