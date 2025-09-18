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
# Rocketmq-IoTDB Demo
## Introduction
This demo shows how to store data into IoTDB via rocketmq
## Basic Concept
The following basic concepts are involved in IoTDB:

* Device

A devices is an installation equipped with measurements in real scenarios. In IoTDB, all measurements should have their corresponding devices.

* Measurement

A measurement is a detection equipment in an actual scene, which can sense the information to be measured, and can transform the sensed information into an electrical signal or other desired form of information output and send it to IoTDB. In IoTDB, all data and paths stored are organized in units of sensors.

* Database

Databases are used to let users define how to organize and isolate different time series data on disk. Time series belonging to the same database will be continuously written to the same file in the corresponding folder. The file may be closed due to user commands or system policies, and hence the data coming next from these measurements will be stored in a new file in the same folder. Time series belonging to different databases are stored in different folders.
## Connector
> note:In this sample program, there are some update operations for historical data, so it is necessary to ensure the sequential transmission and consumption of data via RocketMQ. If there is no update operation in use, then there is no need to guarantee the order of data. IoTDB will process these data which may be disorderly.

### Producer
Producers insert IoTDB insert statements into partitions according to devices, ensuring that the same device's data is inserted or updated in the same MessageQueue.
### Consumer 
1. At startup, the consumer client first creates a IOTDB-Session connection and check whether the databases and timeseries are created in IoTDB. If not, create it.  
2. Then consume client consume data from RocketMQ using MessageListener Orderly to ensure orderly consumption, and insert the sql statement into IoTDB.

## Usage
### Version usage

|          | Version |
|----------|---------|
| IoTDB    | 2.0.5   |  
| RocketMQ | 5.3.3   |

### Dependencies with Maven

```
<dependencies>
    <dependency>
        <groupId>org.apache.iotdb</groupId>
        <artifactId>iotdb-session</artifactId>
        <version>2.0.5</version>
    </dependency>
    <dependency>
        <groupId>org.apache.rocketmq</groupId>
        <artifactId>rocketmq-client</artifactId>
        <version>5.3.3</version>
    </dependency>
  </dependencies>
```
Note: The maven dependencies of io.netty in IoTDB are in conflicts with those dependencies in RocketMQ-Client.

### Prerequisite Steps

#### 1. Install IoTDB
please refer to [https://iotdb.apache.org/#/Download](https://iotdb.apache.org/#/Download)

#### 2. Install RocketMQ
please refer to [http://rocketmq.apache.org/docs/quick-start/](http://rocketmq.apache.org/docs/quick-start/)

#### 3. Startup IoTDB
please refer to [Quick Start](https://iotdb.apache.org/UserGuide/latest/QuickStart/QuickStart_apache.html)

#### 4. Startup RocketMQ
please refer to [http://rocketmq.apache.org/docs/quick-start/](http://rocketmq.apache.org/docs/quick-start/)

### Case 1: Send data from localhost to IoTDB-tree 

Files related:
1. `Constant.java` : configuration of IoTDB and RocketMQ
2. `RocketMQProducer.java` : send data from localhost to RocketMQ
3. `RocketMQConsumer.java` : consume data from RocketMQ
4. `Utils.java` : utils 

Step 0: Set parameter in `Constant.java`

> Change the parameters according to your situation.

| Parameter                 | Data Type | Description                                                                                                                                                                                                                                          |
|---------------------------|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOPIC                     | String    | The topic to store data in RocketMQ                                                                                                                                                                                                                  |
| SERVER_ADDRESS            | String    | The server address of RocketMQ, e.g. `"127.0.0.1:9876"`                                                                                                                                                                                              |
| PRODUCER_GROUP            | String    | The producer group name in RocketMQ                                                                                                                                                                                                                  |       
| CONSUMER_GROUP            | String    | The consumer group name in RocketMQ                                                                                                                                                                                                                  |
| IOTDB_CONNECTION_HOST     | String    | IoTDB host, e.g. `"localhost"`                                                                                                                                                                                                                       |                                  
| IOTDB_CONNECTION_PORT     | int       | IoTDB port, e.g. `6667`                                                                                                                                                                                                                              |
| IOTDB_CONNECTION_USER     | String    | IoTDB username, e.g. `"root"`                                                                                                                                                                                                                        |
| IOTDB_CONNECTION_PASSWORD | String    | IoTDB password, e.g. `"root"`                                                                                                                                                                                                                        |
| STORAGE_GROUP             | Array     | The storage groups to create                                                                                                                                                                                                                         |
| CREATE_TIMESERIES         | Array     | The timeseries to create <br/> Format of a single timeseries: {"timeseries", "dataType", "encodingType", "compressionType"} <br/> e.g. `{"root.vehicle.d0.s0", "INT32", "PLAIN", "SNAPPY"}`                                                          |
| ALL_DATA                  | Array     | The data to create <br/> Format of a single data: "device,timestamp,fieldName\[:fieldName\]\*,dataType\[:dataType\]\*,value\[:value\]\*" <br/> e.g. `"root.vehicle.d0,10,s0,INT32,100"`, `"root.vehicle.d0,12,s0:s1,INT32:TEXT,101:'employeeId102'"` |

Step 1: Run `RocketMQProducer.java`

> This class sends data from localhost to RocketMQ. <br/>

Step 2: Run `RocketMQConsumer.java`

> This class consumes data from RocketMQ and sends the data to IoTDB-tree.

### Case 2: Send data from localhost to IoTDB-table

Files related:
1. `RelationalConstant.java` : configuration of IoTDB and RocketMQ
2. `RelationalRocketMQProducer.java` : send data from localhost to RocketMQ
3. `RelationalRocketMQConsumer.java` : consume data from RocketMQ
4. `RelationalUtils.java` : utils

Step 0: Set parameter in `RelationalConstant.java`

> Change the parameters according to your situation.

| Parameter      | Data Type | Description                                                                                                                                                                                                                               |
|----------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOPIC          | String    | The topic to store data in RocketMQ                                                                                                                                                                                                       |
| SERVER_ADDRESS | String    | The server address of RocketMQ, e.g. `"127.0.0.1:9876"`                                                                                                                                                                                   |
| PRODUCER_GROUP | String    | The producer group name in RocketMQ                                                                                                                                                                                                       |       
| CONSUMER_GROUP | String    | The consumer group name in RocketMQ                                                                                                                                                                                                       |
| IOTDB_URLS     | Array     | IoTDB urls, e.g. `{"localhost:6667"}`                                                                                                                                                                                                     |                                  
| IOTDB_USERNAME | String    | IoTDB username, e.g. `"root"`                                                                                                                                                                                                             |
| IOTDB_PASSWORD | String    | IoTDB password, e.g. `"root"`                                                                                                                                                                                                             |
| DATABASES      | Array     | The databases to create                                                                                                                                                                                                                   |
| TABLES         | Array     | The tables to create <br/> Format of a single table: {"database", "tableName", "columnNames", "columnTypes", "columnCategories"} <br/> e.g. `{"rocketmq_db1", "tb1", "time,region,status", "TIMESTAMP,STRING,BOOLEAN", "TIME,TAG,FIELD"}` |
| ALL_DATA       | Array     | The data to create <br/> Format of a single data: "database;tableName;columnName\[,columnName\]\*;value\[,value\]\*\[;value\[,value\]\*\]\*" <br/> e.g. `"rocketmq_db1;tb1;time,status;17,true;18,false;19,true"`                         |


Step 1: Run `RelationalRocketMQProducer.java`

> This class sends data from localhost to RocketMQ.

Step 2: Run `RelationalRocketMQConsumer.java`

> This class consumes data from RocketMQ and sends the data to IoTDB-table.
