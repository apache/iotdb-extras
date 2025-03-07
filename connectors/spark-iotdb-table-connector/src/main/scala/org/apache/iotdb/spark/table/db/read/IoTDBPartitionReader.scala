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

import org.apache.iotdb.isession.ITableSession
import org.apache.iotdb.session.TableSessionBuilder
import org.apache.iotdb.spark.table.db.{IoTDBOptions, IoTDBUtils}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReader}
import org.apache.spark.sql.types._

/**
 * IoTDBPartitionReader is responsible for reading data from IoTDB and converting it into Spark's InternalRow format.
 *
 * @param inputPartition The partition containing query information.
 * @param schema The schema of the resulting data.
 * @param options IoTDB connection and query options.
 */
class IoTDBPartitionReader(inputPartition: InputPartition, schema: StructType, options: IoTDBOptions) extends PartitionReader[InternalRow] with Logging {

  private lazy val session: ITableSession = {
    new TableSessionBuilder()
      .username(options.username)
      .password(options.password)
      .nodeUrls(options.urls)
      .database(options.database)
      .build()
  }

  private lazy val dataSetIterator = session.executeQueryStatement(inputPartition.asInstanceOf[IoTDBInputPartition].getSQL).iterator()

  override def next(): Boolean = dataSetIterator.next()

  override def get(): InternalRow = {
    val row = new GenericInternalRow(schema.length)
    for (i <-  0 until schema.length) {
      if (dataSetIterator.isNull(i + 1)) {
        row.setNullAt(i)
      } else {
        val dataType = schema.fields(i).dataType
        row.update(i,  IoTDBUtils.getSparkValue(dataType, dataSetIterator, i + 1))
      }
    }
    row
  }

  override def close(): Unit = {
    try {
      if (session != null) {
        session.close()
      }
    } catch {
      case e: Exception =>
        logError(s"Error closing IoTDB session: ${e.getMessage}")
    }
  }
}
