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

package org.apache.iotdb.spark.table.db.write

import org.apache.iotdb.session.TableSessionBuilder
import org.apache.iotdb.spark.table.db.{IoTDBOptions, IoTDBUtils}
import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.write.{DataWriter, WriterCommitMessage}
import org.apache.spark.sql.types.{NullType, StructType}
import org.apache.tsfile.enums.TSDataType
import org.apache.tsfile.write.record.Tablet
import org.apache.tsfile.write.record.Tablet.ColumnCategory

class IoTDBDataWriter(options: IoTDBOptions, writeSchema: StructType, tableSchema: StructType) extends DataWriter[InternalRow] with Logging {

  private lazy val session =
    new TableSessionBuilder()
      .username(options.username)
      .password(options.password)
      .database(options.database)
      .nodeUrls(options.urls)
      .build()

  private val tableSchemaMap = tableSchema.fields.map(f => f.name -> f).toMap

  private val isWriteSchemaValid = writeSchema.fields.forall(f => tableSchemaMap.contains(f.name))

  private lazy val tablet = {
    val tableName = options.table
    val columnNameList = new java.util.ArrayList[String]()
    val dataTypeList = new java.util.ArrayList[TSDataType]()
    val columnCategoryList = new java.util.ArrayList[ColumnCategory]()

    for (i <- writeSchema.indices) {
      val writeSchemaField = writeSchema.fields(i)
      val fieldInTableSchema = if (isWriteSchemaValid) {
        writeSchema.fields(i)
      } else {
        tableSchema.fields(i)
      }
      val actualColumnSchema = tableSchemaMap.getOrElse(fieldInTableSchema.name, tableSchema.fields(i))
      val columnCategoryStr = actualColumnSchema.metadata.getString(IoTDBUtils.COLUMN_CATEGORY)
      val columnCategory = IoTDBUtils.getIoTDBColumnCategory(columnCategoryStr)
      if (fieldInTableSchema.name != IoTDBUtils.TIME) {
        val dataType = if (writeSchemaField.dataType == NullType) {
          actualColumnSchema.dataType
        } else {
          writeSchemaField.dataType
        }
        columnNameList.add(fieldInTableSchema.name)
        dataTypeList.add(IoTDBUtils.getIoTDBDataType(dataType))
        columnCategoryList.add(columnCategory)
      }
    }
    new Tablet(tableName, columnNameList, dataTypeList, columnCategoryList)
  }

  override def write(record: InternalRow): Unit = {
    if (tablet.getRowSize == tablet.getMaxRowNumber) {
      writeTabletToIoTDB()
    }
    val currentRow = tablet.getRowSize
    try {
      for (i <- writeSchema.fields.indices) {
        if (!record.isNullAt(i)) {
          val column = if (isWriteSchemaValid) {
            writeSchema.fields(i).name
          } else {
            tableSchema.fields(i).name
          }
          val dataType = writeSchema.fields(i).dataType
          val value = IoTDBUtils.getIoTDBValue(dataType, record.get(i, dataType))
          if (column == IoTDBUtils.TIME) {
            tablet.addTimestamp(currentRow, value.asInstanceOf[Long])
          } else {
            tablet.addValue(column, currentRow, value)
          }
        }
      }
    } catch {
      case e: Exception =>
        throw SparkException.internalError("Error writing data to Tablet", e)
    }
  }

  override def commit(): WriterCommitMessage = {
    if (tablet.getRowSize > 0) {
      writeTabletToIoTDB()
    }
    new IoTDBWriterCommitMessage()
  }

  private def writeTabletToIoTDB(): Unit = {
    try {
      session.insert(tablet)
      tablet.reset()
    } catch {
      case e: Exception =>
        throw SparkException.internalError("Error writing tablet to IoTDB", e)
    }
  }

  override def abort(): Unit = {}

  override def close(): Unit = {
    if (session != null) {
      try {
        session.close()
      } catch {
        case e: Exception =>
          logError(s"Error closing IoTDB session: ${e.getMessage}")
      }
    }
  }
}

class IoTDBWriterCommitMessage extends WriterCommitMessage {}
