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
# IoTDB-Table-Spark-Connector Example
## Introduction
This example demonstrates how to use the IoTDB-Table-Spark-Connector to read and write data from/to IoTDB in Spark.
## Version
* Scala 2.12
* Spark 3.3 or later
## Usage
Import the IoTDB-Table-Spark-Connector dependency in your project.
```
<dependency>
    <groupId>org.apache.iotdb</groupId>
    <artifactId>spark-iotdb-table-connector-3.5</artifactId>
</dependency>
```
## Options
| Key            | Default Value  | Comment                                                                                                   | Required |
|----------------|----------------|-----------------------------------------------------------------------------------------------------------|----------|
| iotdb.database | --             | The database name of Iotdb, which needs to be a database that already exists in IoTDB                     | true     |
| iotdb.table    | --             | The table name in IoTDB needs to be a table that already exists in IoTDB                                  | true     |
| iotdb.username | root           | the username to access IoTDB                                                                              | false    |
| iotdb.password | root           | the password to access IoTDB                                                                              | false    |
| iotdb.urls     | 127.0.0.1:6667 | The url for the client to connect to the datanode rpc. If there are multiple urls, separate them with ',' | false    |


## Read
### DataFrame
```scala
val df = spark.read.format("org.apache.iotdb.spark.table.db.IoTDBTableProvider")
  .option("iotdb.database", "$YOUR_IOTDB_DATABASE_NAME")
  .option("iotdb.table", "$YOUR_IOTDB_TABLE_NAME")
  .option("iotdb.username", "$YOUR_IOTDB_USERNAME")
  .option("iotdb.password", "$YOUR_IOTDB_PASSWORD")
  .option("iotdb.url", "$YOUR_IOTDB_URL")
  .load()
```
### Spark SQL
```
CREATE TEMPORARY VIEW spark_iotdb
   USING org.apache.iotdb.spark.table.db.IoTDBTableProvider
   OPTIONS(
   "iotdb.database"="$YOUR_IOTDB_DATABASE_NAME",
   "iotdb.table"="$YOUR_IOTDB_TABLE_NAME",
   "iotdb.username"="$YOUR_IOTDB_USERNAME",
   "iotdb.password"="$YOUR_IOTDB_PASSWORD",
   "iotdb.urls"="$YOUR_IOTDB_URL"
);

SELECT * FROM spark_iotdb;
```

## Write
### DataFrame
```scala
val df = spark.createDataFrame(List(
  (1L, "tag1_value1", "tag2_value1", "attribute1_value1", 1, true),
  (1L, "tag1_value1", "tag2_value2", "attribute1_value1", 2, false)))
  .toDF("time", "tag1", "tag2", "attribute1", "s1", "s2")

df
  .write
  .format("org.apache.iotdb.spark.table.db.IoTDBTableProvider")
  .option("iotdb.database", "$YOUR_IOTDB_DATABASE_NAME")
  .option("iotdb.table", "$YOUR_IOTDB_TABLE_NAME")
  .option("iotdb.username", "$YOUR_IOTDB_USERNAME")
  .option("iotdb.password", "$YOUR_IOTDB_PASSWORD")
  .option("iotdb.urls", "$YOUR_IOTDB_URL")
  .save()
```
### Spark SQL
```
CREATE TEMPORARY VIEW spark_iotdb
   USING org.apache.iotdb.spark.table.db.IoTDBTableProvider
   OPTIONS(
   "iotdb.database"="$YOUR_IOTDB_DATABASE_NAME",
   "iotdb.table"="$YOUR_IOTDB_TABLE_NAME",
   "iotdb.username"="$YOUR_IOTDB_USERNAME",
   "iotdb.password"="$YOUR_IOTDB_PASSWORD",
   "iotdb.urls"="$YOUR_IOTDB_URL"
);

INSERT INTO spark_iotdb VALUES ("VALUE1", "VALUE2", ...);
INSERT INTO spark_iotdb SELECT * FROM YOUR_TABLE
```