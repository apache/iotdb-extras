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
# Kafka-IoTDB Demo
## Function
```
The example is to show how to send data from localhost to IoTDB through Kafka.
```
## Usage
### Version usage

|       | Version |
|-------|---------|
| IoTDB | 2.0.5   |  
| Kafka | 2.8.2   |

### Dependencies with Maven

```
<dependencies>
    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>kafka_2.13</artifactId>
        <version>2.8.2</version>
    </dependency>
    <dependency>
        <groupId>org.apache.iotdb</groupId>
        <artifactId>iotdb-session</artifactId>
        <version>2.0.5</version>
    </dependency>
</dependencies>
```

### Prerequisite Steps

#### 1. Install IoTDB
please refer to [https://iotdb.apache.org/#/Download](https://iotdb.apache.org/#/Download)

#### 2. Install Kafka
please refer to [https://kafka.apache.org/downloads](https://kafka.apache.org/downloads)

#### 3. Startup IoTDB
please refer to [Quick Start](http://iotdb.apache.org/UserGuide/Master/Get%20Started/QuickStart.html)

#### 4. Startup Kafka
please refer to [https://kafka.apache.org/quickstart](https://kafka.apache.org/quickstart)


### Case 1: Send data from localhost to IoTDB-tree

Files related:
1. `Constant.java` : configuration of IoTDB and Kafka
2. `Producer.java` : send data from localhost to Kafka cluster
3. `Consumer.java` : consume data from Kafka cluster through multi-threads
4. `ConsumerThread.java` : consume operations done by single thread

Step 0: Set parameter in `Constant.java`

> Change the parameters according to your situation.

| Parameter                 | Data Type | Description                                                                                                                                                                                                                                          |
|---------------------------|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOPIC                     | String    | The topic to store data in Kafka                                                                                                                                                                                                                     |
| KAFKA_SERVICE_URL         | String    | The service url of Kafka, e.g. `"127.0.0.1:9092"`                                                                                                                                                                                                    |
| CONSUMER_THREAD_NUM       | int       | The number of consumer threads                                                                                                                                                                                                                       |       
| SESSION_SIZE              | int       | The maximum number of IoTDB sessions                                                                                                                                                                                                                 |
| IOTDB_CONNECTION_HOST     | String    | IoTDB host, e.g. `"localhost"`                                                                                                                                                                                                                       |                                  
| IOTDB_CONNECTION_PORT     | int       | IoTDB port, e.g. `6667`                                                                                                                                                                                                                              |
| IOTDB_CONNECTION_USER     | String    | IoTDB username, e.g. `"root"`                                                                                                                                                                                                                        |
| IOTDB_CONNECTION_PASSWORD | String    | IoTDB password, e.g. `"root"`                                                                                                                                                                                                                        |
| STORAGE_GROUP             | Array     | The storage groups to create                                                                                                                                                                                                                         |
| CREATE_TIMESERIES         | Array     | The timeseries to create <br/> Format of a single timeseries: {"timeseries", "dataType", "encodingType", "compressionType"} <br/> e.g. `{"root.vehicle.d0.s0", "INT32", "PLAIN", "SNAPPY"}`                                                          |
| ALL_DATA                  | Array     | The data to create <br/> Format of a single data: "device,timestamp,fieldName\[:fieldName\]\*,dataType\[:dataType\]\*,value\[:value\]\*" <br/> e.g. `"root.vehicle.d0,10,s0,INT32,100"`, `"root.vehicle.d0,12,s0:s1,INT32:TEXT,101:'employeeId102'"` |

Step 1: Run `Producer.java`

> This class sends data from localhost to Kafka clusters. <br/>

Step 2: Run `Consumer.java`

> This class consumes data from Kafka through multi-threads and sends the data to IoTDB-tree.

### Case 2: Send data from localhost to IoTDB-table

Files related:
1. `RelationalConstant.java` : configuration of IoTDB and Kafka
2. `RelationalProducer.java` : send data from localhost to Kafka cluster
3. `RelationalConsumer.java` : consume data from Kafka cluster through multi-threads
4. `RelationalConsumerThread.java` : consume operations done by single thread

Step 0: Set parameter in `RelationalConstant.java`

> Change the parameters according to your situation.

| Parameter           | Data Type | Description                                                                                                                                                                                                                            |
|---------------------|-----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOPIC               | String    | The topic to store data in Kafka                                                                                                                                                                                                       |
| KAFKA_SERVICE_URL   | String    | The service url of Kafka, e.g. `"127.0.0.1:9092"`                                                                                                                                                                                      |
| CONSUMER_THREAD_NUM | int       | The number of consumer threads                                                                                                                                                                                                         |       
| SESSION_SIZE        | int       | The maximum number of IoTDB sessions                                                                                                                                                                                                   |
| IOTDB_URLS          | Array     | IoTDB urls, e.g. `{"localhost:6667"}`                                                                                                                                                                                                  |                                  
| IOTDB_USERNAME      | String    | IoTDB username, e.g. `"root"`                                                                                                                                                                                                          |
| IOTDB_PASSWORD      | String    | IoTDB password, e.g. `"root"`                                                                                                                                                                                                          |
| DATABASES           | Array     | The databases to create                                                                                                                                                                                                                |
| TABLES              | Array     | The tables to create <br/> Format of a single table: {"database", "tableName", "columnNames", "columnTypes", "columnCategories"} <br/> e.g. `{"kafka_db1", "tb1", "time,region,status", "TIMESTAMP,STRING,BOOLEAN", "TIME,TAG,FIELD"}` |
| ALL_DATA            | Array     | The data to create <br/> Format of a single data: "database;tableName;columnName\[,columnName\]\*;value\[,value\]\*\[;value\[,value\]\*\]\*" <br/> e.g. `"kafka_db1;tb1;time,status;17,true;18,false;19,true"`                         |

Step 1: Run `RelationalProducer.java`

> This class sends data from localhost to Kafka clusters. 

Step 2: Run `RelationalConsumer.java`

> This class consumes data from Kafka through multi-threads and sends the data to IoTDB-table.


### Notice 
If you want to use multiple consumers, please make sure that the number of topic's partition you create is more than 1.