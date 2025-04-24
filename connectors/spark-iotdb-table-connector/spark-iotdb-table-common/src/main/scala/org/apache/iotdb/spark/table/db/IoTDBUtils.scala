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

package org.apache.iotdb.spark.table.db

import org.apache.iotdb.isession.SessionDataSet
import org.apache.iotdb.session.TableSessionBuilder
import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.tsfile.enums.TSDataType
import org.apache.tsfile.read.common.RowRecord
import org.apache.tsfile.utils.{Binary, DateUtils}
import org.apache.tsfile.write.record.Tablet.ColumnCategory

import java.util

object IoTDBUtils {

  val TIME = "time"
  val COLUMN_CATEGORY = "category"

  /**
   * Retrieves the schema of an IoTDB table.
   *
   * @param options IoTDB options.
   * @return The schema as a Spark `StructType`.
   */
  def getSchema(options: IoTDBOptions): StructType = {
    val session = new TableSessionBuilder()
      .username(options.username)
      .password(options.password)
      .nodeUrls(options.urls)
      .database(options.database)
      .build()
    val structFields = new util.ArrayList[StructField]()
    var dataSet: SessionDataSet = null
    try {
      dataSet = session.executeQueryStatement(s"DESC ${options.table}")
      while (dataSet.hasNext) {
        val row: RowRecord = dataSet.next()
        val columnName = row.getField(0).getStringValue
        val dataType = row.getField(1).getStringValue
        val columnType = row.getField(2).getStringValue
        structFields.add(StructField(columnName, getSparkDataType(dataType), nullable = !TIME.equals(columnName), metadata = new MetadataBuilder().putString(COLUMN_CATEGORY, columnType).build()))
      }
    } catch {
      case e: Exception => throw SparkException.internalError(s"Failed to get schema of table ${options.table}.", e)
    } finally {
      if (dataSet != null) {
        dataSet.close()
      }
      session.close()
    }
    new StructType(structFields.toArray(Array[StructField]()))
  }

  private def getSparkDataType(iotdbDataTypeStr: String): DataType = {
    iotdbDataTypeStr.toUpperCase match {
      case "BOOLEAN" => BooleanType
      case "INT32" => IntegerType
      case "DATE" => DateType
      case "INT64" => LongType
      case "TIMESTAMP" => LongType
      case "FLOAT" => FloatType
      case "DOUBLE" => DoubleType
      case "TEXT" => StringType
      case "BLOB" => BinaryType
      case "STRING" => StringType
      case _ => StringType
    }
  }

  def getSparkValue(sparkDataType: DataType, dataSetIterator: SessionDataSet#DataIterator, columnIdx: Int): Any = {
    sparkDataType match {
      case BooleanType => dataSetIterator.getBoolean(columnIdx)
      case IntegerType => dataSetIterator.getInt(columnIdx)
      case DateType => DateTimeUtils.fromJavaDate(DateUtils.parseIntToDate(dataSetIterator.getInt(columnIdx)))
      case LongType => dataSetIterator.getLong(columnIdx)
      case FloatType => dataSetIterator.getFloat(columnIdx)
      case DoubleType => dataSetIterator.getDouble(columnIdx)
      case StringType => UTF8String.fromString(dataSetIterator.getString(columnIdx))
      case BinaryType => getByteArrayFromHexString(dataSetIterator.getString(columnIdx))
      case TimestampType => dataSetIterator.getLong(columnIdx)
    }
  }

  private def getByteArrayFromHexString(value: String): Array[Byte] = {
    if (value.isEmpty) {
      new Array[Byte](0)
    }
    require(value.length % 2 == 0, "The length of the hex string must be even.")
    value.substring(2).sliding(2, 2).map(Integer.parseInt(_, 16).toByte).toArray
  }

  def getIoTDBHexStringFromByteArray(value: Array[Byte]): String = {
    s"X'${value.map(b => f"$b%02X").mkString("")}'"
  }

  def getIoTDBDataType(sparkDataType: DataType): TSDataType = {
    sparkDataType match {
      case BooleanType => TSDataType.BOOLEAN
      case ByteType => TSDataType.INT32
      case ShortType => TSDataType.INT32
      case IntegerType => TSDataType.INT32
      case LongType => TSDataType.INT64
      case FloatType => TSDataType.FLOAT
      case DoubleType => TSDataType.DOUBLE
      case StringType => TSDataType.STRING
      case BinaryType => TSDataType.BLOB
      case DateType => TSDataType.DATE
      case TimestampType => TSDataType.STRING
      case _ => {
        var errMsg = s"Unable to convert Spark data type $sparkDataType to IoTDB data type."
        if (sparkDataType.simpleString.toLowerCase.contains("decimal")) {
          errMsg += s"For float numbers in insert into values sql, you should add the suffix 'f' or 'd' to represent float or double."
        }
        throw new IllegalArgumentException(errMsg)
      }
    }
  }

  def getIoTDBValue(sparkDataType: DataType, value: Any): Any = {
    sparkDataType match {
      case BooleanType => value.asInstanceOf[Boolean]
      case ByteType => value.asInstanceOf[Byte].toInt
      case ShortType => value.asInstanceOf[Short].toInt
      case IntegerType => value.asInstanceOf[Int]
      case LongType => value.asInstanceOf[Long]
      case FloatType => value.asInstanceOf[Float]
      case DoubleType => value.asInstanceOf[Double]
      case StringType => value.asInstanceOf[UTF8String].toString
      case BinaryType => new Binary(value.asInstanceOf[Array[Byte]])
      case DateType => DateTimeUtils.toJavaDate(value.asInstanceOf[Integer]).toLocalDate
      case TimestampType => DateTimeUtils.toJavaTimestamp(value.asInstanceOf[Long]).toString
      case _ => value.toString
    }
  }

  def getIoTDBColumnCategory(columnCategoryStr: String): ColumnCategory = {
    columnCategoryStr.toUpperCase match {
      case "TAG" => ColumnCategory.TAG
      case "ATTRIBUTE" => ColumnCategory.ATTRIBUTE
      case _ => ColumnCategory.FIELD
    }
  }

  def getIoTDBColumnIdentifierInSQL(sparkColumnIdentifier: String, isSparkNamedReference: Boolean): String = {
    var str = sparkColumnIdentifier
    if (isSparkNamedReference) {
      str = sparkColumnIdentifier.replaceAll("``", "`")
      if (str.startsWith("`") && str.endsWith("`")) {
        str = str.substring(1, str.length - 1)
      }
    }
    str = str.replaceAll("\"", "\"\"")
    s""""$str""""
  }

}
