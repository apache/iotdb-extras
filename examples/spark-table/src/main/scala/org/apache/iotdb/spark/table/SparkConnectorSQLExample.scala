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

object SparkConnectorSQLExample {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("IoTDB Spark Demo")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.master", "local[*]")
      .getOrCreate()
    spark.sql(
      """
        CREATE TEMPORARY VIEW spark_iotdb1
                 USING org.apache.iotdb.spark.table.db.IoTDBTableProvider
                 OPTIONS(
                 "iotdb.database"="test",
                 "iotdb.table"="table1",
                 "iotdb.username"="root",
                 "iotdb.password"="root",
                 "iotdb.url"="127.0.0.1:6667");
        """)
    spark.sql(
      """
        CREATE TEMPORARY VIEW spark_iotdb2
                 USING org.apache.iotdb.spark.table.db.IoTDBTableProvider
                 OPTIONS(
                 "iotdb.database"="test",
                 "iotdb.table"="table2",
                 "iotdb.username"="root",
                 "iotdb.password"="root",
                 "iotdb.urls"="127.0.0.1:6667");
        """)
    spark.sql("select * from spark_iotdb1").show
    spark.sql("insert into spark_iotdb2 select time,tag1, s0, s1 from spark_iotdb1")
    spark.sql("select * from spark_iotdb1").show
    spark.close()

  }
}
