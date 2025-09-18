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
# RabbitMQ-IoTDB Demo
## Function
```
The example is to show how to send data from localhost to IoTDB through RabbitMQ.
```
## Usage
### Version usage

|          | Version |
|----------|---------|
| IoTDB    | 2.0.5   |  
| RabbitMQ | 5.26.0  |

### Dependencies with Maven

```
<dependencies>
    <dependency>
        <groupId>org.apache.iotdb</groupId>
        <artifactId>iotdb-session</artifactId>
        <version>2.0.5</version>
    </dependency>
    <dependency>
        <groupId>com.rabbitmq</groupId>
        <artifactId>amqp-client</artifactId>
        <version>5.26.0</version>
    </dependency>
</dependencies>
```

### Prerequisite Steps

#### 1. Install IoTDB
please refer to [https://iotdb.apache.org/#/Download](https://iotdb.apache.org/#/Download)

#### 2. Install RabbitMQ
please refer to [https://www.rabbitmq.com/download.html](https://www.rabbitmq.com/download.html)

#### 3. Startup IoTDB
please refer to [Quick Start](http://iotdb.apache.org/UserGuide/Master/Get%20Started/QuickStart.html)

#### 4. Startup RocketMQ
please refer to [https://www.rabbitmq.com/download.html](https://www.rabbitmq.com/download.html)

### Case 1: Send data from localhost to IoTDB-tree

Files related:
1. `Constant.java` : configuration of IoTDB and RabbitMQ
2. `RabbitMQProducer.java` : send data from localhost to RabbitMQ
3. `RabbitMQConsumer.java` : consume data from RabbitMQ
4. `RabbitMQChannelUtils.java` : common functions

Step 0: Set parameter in `Constant.java`

> Change the parameters according to your situation.

| Parameter                 | Data Type | Description                                                                                                                                                                                                                                          |
|---------------------------|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOPIC                     | String    | The topic to store data in Kafka                                                                                                                                                                                                                     |
| SERVER_HOST               | String    | The server ip of RabbitMQ, e.g. `"localhost"`                                                                                                                                                                                                        |
| SERVER_PORT               | int       | The server port of RabbitMQ, e.g. `5672`                                                                                                                                                                                                             |
| RABBITMQ_VHOST            | String    | The virtual host in RabbitMQ, e.g. `"/"`                                                                                                                                                                                                             |
| RABBITMQ_USERNAME         | String    | RabbitMQ username, e.g. `"guest"`                                                                                                                                                                                                                    |
| RABBITMQ_PASSWORD         | String    | RabbitMQ password, e.g. `"guest"`                                                                                                                                                                                                                    |
| CONNECTION_NAME           | String    | RabbitMQ connection name                                                                                                                                                                                                                             |
| RABBITMQ_CONSUMER_QUEUE   | String    | The consumer queue name in RabbitMQ                                                                                                                                                                                                                  |
| RABBITMQ_CONSUMER_TAG     | String    | The consumer tag in RabbitMQ                                                                                                                                                                                                                         |   
| IOTDB_CONNECTION_HOST     | String    | IoTDB host, e.g. `"localhost"`                                                                                                                                                                                                                       |                                  
| IOTDB_CONNECTION_PORT     | int       | IoTDB port, e.g. `6667`                                                                                                                                                                                                                              |
| IOTDB_CONNECTION_USER     | String    | IoTDB username, e.g. `"root"`                                                                                                                                                                                                                        |
| IOTDB_CONNECTION_PASSWORD | String    | IoTDB password, e.g. `"root"`                                                                                                                                                                                                                        |
| STORAGE_GROUP             | Array     | The storage groups to create                                                                                                                                                                                                                         |
| TIMESERIESLIST            | Array     | The timeseries to create <br/> Format of a single timeseries: {"timeseries", "dataType", "encodingType", "compressionType"} <br/> e.g. `{"root.vehicle.d0.s0", "INT32", "PLAIN", "SNAPPY"}`                                                          |
| ALL_DATA                  | Array     | The data to create <br/> Format of a single data: "device,timestamp,fieldName\[:fieldName\]\*,dataType\[:dataType\]\*,value\[:value\]\*" <br/> e.g. `"root.vehicle.d0,10,s0,INT32,100"`, `"root.vehicle.d0,12,s0:s1,INT32:TEXT,101:'employeeId102'"` |


Step 1: Run `RabbitMQProducer.java`

> This class sends data from localhost to RabbitMQ. 

Step 2: Run `RabbitMQConsumer.java`

> This class consumes data from RabbitMQ and sends the data to IoTDB-tree.

### Case 2: Send data from localhost to IoTDB-table

Files related:
1. `RelationalConstant.java` : configuration of IoTDB and RabbitMQ
2. `RelationalRabbitMQProducer.java` : send data from localhost to RabbitMQ
3. `RelationalRabbitMQConsumer.java` : consume data from RabbitMQ
4. `RabbitMQChannelUtils.java` : common functions

Step 0: Set parameter in `RelationalConstant.java`

> Change the parameters according to your situation.

| Parameter               | Data Type | Description                                                                                                                                                                                                                               |
|-------------------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOPIC                   | String    | The topic to store data in Kafka                                                                                                                                                                                                          |
| SERVER_HOST             | String    | The server ip of RabbitMQ, e.g. `"localhost"`                                                                                                                                                                                             |
| SERVER_PORT             | int       | The server port of RabbitMQ, e.g. `5672`                                                                                                                                                                                                  |
| RABBITMQ_VHOST          | String    | The virtual host in RabbitMQ, e.g. `"/"`                                                                                                                                                                                                  |
| RABBITMQ_USERNAME       | String    | RabbitMQ username, e.g. `"guest"`                                                                                                                                                                                                         |
| RABBITMQ_PASSWORD       | String    | RabbitMQ password, e.g. `"guest"`                                                                                                                                                                                                         |
| CONNECTION_NAME         | String    | RabbitMQ connection name                                                                                                                                                                                                                  |
| RABBITMQ_CONSUMER_QUEUE | String    | The consumer queue name in RabbitMQ                                                                                                                                                                                                       |
| RABBITMQ_CONSUMER_TAG   | String    | The consumer tag in RabbitMQ                                                                                                                                                                                                              |   
| IOTDB_URLS              | Array     | IoTDB urls, e.g. `{"localhost:6667"}`                                                                                                                                                                                                     |                                  
| IOTDB_USERNAME          | String    | IoTDB username, e.g. `"root"`                                                                                                                                                                                                             |
| IOTDB_PASSWORD          | String    | IoTDB password, e.g. `"root"`                                                                                                                                                                                                             |
| DATABASES               | Array     | The databases to create                                                                                                                                                                                                                   |
| TABLES                  | Array     | The tables to create <br/> Format of a single table: {"database", "tableName", "columnNames", "columnTypes", "columnCategories"} <br/> e.g. `{"rabbitmq_db1", "tb1", "time,region,status", "TIMESTAMP,STRING,BOOLEAN", "TIME,TAG,FIELD"}` |
| ALL_DATA                | Array     | The data to create <br/> Format of a single data: "database;tableName;columnName\[,columnName\]\*;value\[,value\]\*\[;value\[,value\]\*\]\*" <br/> e.g. `"rabbitmq_db1;tb1;time,status;17,true;18,false;19,true"`                         |

Step 1: Run `RabbitMQProducer.java`

> This class sends data from localhost to RabbitMQ.

Step 2: Run `RabbitMQConsumer.java`

> This class consumes data from RabbitMQ and sends the data to IoTDB-table.

