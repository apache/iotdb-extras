@REM
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM     http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM

@echo off

set current_dir=%~dp0
set superior_dir=%current_dir%\..\

IF EXIST "%superior_dir%\conf\application.properties" (
  set config_file="%superior_dir%\conf\application.properties"
) ELSE (
  IF EXIST "%superior_dir%\conf\application.properties" (
    set config_file=%superior_dir%\conf\application.properties
  ) ELSE (
    echo No configuration file found. Exiting.
    exit /b 1
  )
)

if not defined config_file (
  echo No configuration file found. Exiting.
  exit /b 1
)

for /f  "eol=# tokens=2 delims==" %%i in ('findstr /i "^api_service_port"
"%config_file%"') do (
  set api_service_port=%%i
)

if not defined api_service_port (
  echo "WARNING: api_service_port not found in the configuration file. Using default value api_service_port = 17070"
  set api_service_port=17070
)

echo Check whether the rpc_port is used..., port is %dn_rpc_port%

for /f  "eol=# tokens=2 delims==" %%i in ('findstr /i "api_service_address"
"%config_file%"') do (
  set api_service_address=%%i
)

if not defined api_service_address (
  echo "WARNING: api_service_address not found in the configuration file. Using default value api_service_address = 0.0.0.0"
  set api_service_address=0.0.0.0
)

for /f "tokens=5" %%a in ('netstat /ano ^| findstr %api_service_address%:%api_service_port%') do (
  taskkill /f /pid %%a
  echo Close DataNode, PID: %%a
)
rem ps ax | grep -i 'iotdb.Application' | grep -v grep | awk '{print $1}' | xargs kill -SIGTERM
