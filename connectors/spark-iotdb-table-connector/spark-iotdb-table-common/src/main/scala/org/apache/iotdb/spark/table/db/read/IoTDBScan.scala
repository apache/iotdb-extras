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

package org.apache.iotdb.spark.table.db.read

import org.apache.iotdb.spark.table.db.{IoTDBOptions, IoTDBUtils}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.connector.read.{Batch, InputPartition, PartitionReaderFactory, Scan}
import org.apache.spark.sql.types.StructType

import scala.language.postfixOps

class IoTDBScan(options :IoTDBOptions, requiredSchema: StructType, pushedFilters: Array[String], pushDownOffset: Int, pushDownLimit: Int) extends Scan with Batch with Logging {

  override def readSchema(): StructType = requiredSchema

  override def toBatch: Batch = this

  override def planInputPartitions(): Array[InputPartition] = {
    val sql = buildSQL()
    logDebug(s"SQL: $sql")
    Array(new IoTDBInputPartition(sql))
  }

  private def buildSQL(): String = {
    val columnList = getColumns()
    val sqlBuilder = new StringBuilder(s"SELECT $columnList FROM ${options.table}")

    if (pushedFilters.nonEmpty) sqlBuilder.append(s" WHERE ${pushedFilters.mkString(" AND ")}")
    if (pushDownOffset > 0) sqlBuilder.append(s" OFFSET $pushDownOffset")
    if (pushDownLimit > 0) sqlBuilder.append(s" LIMIT $pushDownLimit")

    sqlBuilder.toString()
  }

  private def getColumns(): String = {
    requiredSchema.fieldNames.map(name => IoTDBUtils.getIoTDBColumnIdentifierInSQL(name, false)).mkString(", ")
  }

  override def createReaderFactory(): PartitionReaderFactory = new IoTDBPartitionReaderFactory(requiredSchema, options)
}
