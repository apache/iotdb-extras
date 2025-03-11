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

import org.apache.iotdb.spark.table.db.read.IoTDBScanBuilder
import org.apache.iotdb.spark.table.db.write.IoTDBWriteBuilder
import org.apache.spark.sql.connector.catalog.{Identifier, SupportsRead, SupportsWrite, Table, TableCapability}
import org.apache.spark.sql.connector.read.ScanBuilder
import org.apache.spark.sql.connector.write.{LogicalWriteInfo, WriteBuilder}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import java.util
import scala.collection.JavaConverters.{mapAsScalaMapConverter, setAsJavaSetConverter}
import scala.language.implicitConversions

/**
 * Represents an IoTDB table in Spark, supporting read and write operations.
 *
 * @param identifier    The unique identifier of the table.
 * @param schema        The schema of the table.
 * @param iotdbOptions  Configuration options for IoTDB.
 */
class IoTDBTable(identifier: Identifier, schema: StructType, iotdbOptions: IoTDBOptions) extends Table with SupportsRead with SupportsWrite {

  override def name(): String = identifier.toString

  override def schema(): StructType = schema

  override def capabilities(): util.Set[TableCapability] = {
    Set(TableCapability.BATCH_READ,
      TableCapability.BATCH_WRITE,
      TableCapability.ACCEPT_ANY_SCHEMA).asJava
  }

  override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = new IoTDBScanBuilder(IoTDBOptions.fromMap(options.asCaseSensitiveMap().asScala.toMap), schema())

  override def newWriteBuilder(info: LogicalWriteInfo): WriteBuilder = {
    val incomingSchema = info.schema()
    if (incomingSchema.fields.length > schema.fields.length) {
      throw new IllegalArgumentException(
        s"The incoming schema has more fields (${incomingSchema.fields.length}) than the table schema (${schema.fields.length})."
      )
    }
    new IoTDBWriteBuilder(iotdbOptions, incomingSchema, schema)
  }
}
