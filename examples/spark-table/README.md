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
    <artifactId>spark-iotdb-table-connector_2.12</artifactId>
    <version>1.3.2</version>
</dependency>
```
## Read
### DataFrame
```scala
val df = spark.read.format("iotdb")
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
   USING iotdb
   OPTIONS(
   "iotdb.database"="$YOUR_IOTDB_DATABASE_NAME",
   "iotdb.table"="$YOUR_IOTDB_TABLE_NAME",
   "iotdb.username"="$YOUR_IOTDB_USERNAME",
   "iotdb.password"="$YOUR_IOTDB_PASSWORD",
   "iotdb.url"="$YOUR_IOTDB_URL"
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
  .format("iotdb")
  .option("iotdb.database", "$YOUR_IOTDB_DATABASE_NAME")
  .option("iotdb.table", "$YOUR_IOTDB_TABLE_NAME")
  .option("iotdb.username", "$YOUR_IOTDB_USERNAME")
  .option("iotdb.password", "$YOUR_IOTDB_PASSWORD")
  .option("iotdb.url", "$YOUR_IOTDB_URL")
  .save()
```
### Spark SQL
```
CREATE TEMPORARY VIEW spark_iotdb
   USING iotdb
   OPTIONS(
   "iotdb.database"="$YOUR_IOTDB_DATABASE_NAME",
   "iotdb.table"="$YOUR_IOTDB_TABLE_NAME",
   "iotdb.username"="$YOUR_IOTDB_USERNAME",
   "iotdb.password"="$YOUR_IOTDB_PASSWORD",
   "iotdb.url"="$YOUR_IOTDB_URL"
);

INSERT INTO spark_iotdb VALUES ("VALUE1", "VALUE2", ...);
INSERT INTO spark_iotdb SELECT * FROM YOUR_TABLE
```