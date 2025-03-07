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

object SparkConnectorWriteExample {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("IoTDB Spark Demo")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.master", "local[*]")
      .getOrCreate()
    // time, tag1 string tag,tag2 string tag, s0 int32, s1 boolean
    val df = spark.createDataFrame(List(
        (1L, "tag1_value1","tag2_value1", 1, false),
        (1L, "tag1_value1","tag2_value1", 1, true),
        (2L, "tag1_value2","tag2_value1")), 2, true)
      .toDF("time", "tag1", "tag2", "s0", "s1")


    df
      .write
      .format("org.apache.iotdb.spark.table.db.IoTDBTableProvider")
      .option("iotdb.database", "test")
      .option("iotdb.table", "spark_table1")
      .option("iotdb.username", "root")
      .option("iotdb.password", "root")
      .option("iotdb.url", "127.0.0.1:6667")
      .mode("append")
      .save()
    spark.close()
  }
}
