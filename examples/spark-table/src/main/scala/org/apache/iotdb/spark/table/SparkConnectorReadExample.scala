/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.spark.table

import org.apache.spark.sql.SparkSession

object SparkConnectorReadExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("IoTDB Spark Demo")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.master", "local[*]")
      .getOrCreate()
    val df = spark.read.format("org.apache.iotdb.spark.table.db.IoTDBTableProvider")
      .option("iotdb.database", "test")
      .option("iotdb.table", "table1")
      .option("iotdb.username", "root")
      .option("iotdb.password", "root")
      .option("iotdb.urls", "127.0.0.1:6667")
      .load()
    df.createTempView("iotdb_table1")
    df.printSchema()
    spark.sql("select * from iotdb_table1").show()
    spark.close()
  }
}
