<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->
# IoTDB-Spring-Boot-Starter Demo
## Introduction

    This demo shows how to use iotdb-spring-boot-starter

### Version usage

    IoTDB: 2.0.1-beta
    iotdb-spring-boot-starter: 2.0.2-SNAPSHOT

### 1. Install IoTDB

    please refer to [https://iotdb.apache.org/#/Download](https://iotdb.apache.org/#/Download)

### 2. Startup IoTDB

    please refer to [Quick Start](http://iotdb.apache.org/UserGuide/Master/Get%20Started/QuickStart.html)
    
    Then we need to create a database 'wind' by cli in table model
    ```
    create database wind;
    use wind;
    ```
    Then we need to create a database 'table'
    ```
    create table power_data_set(
        "device_id"    STRING TAG,
        "deivce_name" STRING TAG,
        "type"     INT32 FIELD,
        "name"    DOUBLE FIELD,
        "server_url_master"      FLOAT FIELD,
        "server_url_slave"      BOOLEAN FIELD,
        "prometheus_url_master"      STRING FIELD,
        "prometheus_url_slave"      BLOB FIELD,
        "username"    STRING ATTRIBUTE,
        "create_time"     DATE FIELD,
        "update_time"     DATE FIELD);
    ```

### 3. Build Dependencies with Maven in your Project

    ```
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter</artifactId>
            </dependency>
    
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-test</artifactId>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.apache.iotdb</groupId>
                <artifactId>iotdb-spring-boot-starter</artifactId>
                <version>2.0.2-SNAPSHOT</version>
            </dependency>
        </dependencies>
    ```

### 4、Use The target Bean with @Autowired
    
    You can use the target Bean in your Project,like:
    ```
        @Autowired
        private ITableSessionPool ioTDBSessionPool;
        @Autowired
        private SessionPool sessionPool;

        public void queryTableSessionPool() throws IoTDBConnectionException, StatementExecutionException {
            ITableSession tableSession = ioTDBSessionPool.getSession();
            final SessionDataSet sessionDataSet = tableSession.executeQueryStatement("select * from power_data_set limit 10");
            while (sessionDataSet.hasNext()) {
                final RowRecord rowRecord = sessionDataSet.next();
                final List<Field> fields = rowRecord.getFields();
                for (Field field : fields) {
                    System.out.print(field.getStringValue());
                }
                System.out.println();
            }
        }

        public void querySessionPool() throws IoTDBConnectionException, StatementExecutionException {
            final SessionDataSetWrapper sessionDataSetWrapper = sessionPool.executeQueryStatement("show databases");
            while (sessionDataSetWrapper.hasNext()) {
                final RowRecord rowRecord = sessionDataSetWrapper.next();
                final List<Field> fields = rowRecord.getFields();
                for (Field field : fields) {
                    System.out.print(field.getStringValue());
                }
                System.out.println();
            }
        }

    ```
