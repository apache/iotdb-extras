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

import org.apache.spark.sql.connector.catalog.{Identifier, Table, TableProvider}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import java.util
import scala.collection.JavaConverters.mapAsScalaMapConverter

/**
 * IoTDBTableProvider is a Spark DataSource V2 provider for IoTDB.
 * It supports schema inference and table access.
 */
abstract class AbstractIoTDBTableProvider extends TableProvider {

  override def inferSchema(caseInsensitiveStringMap: CaseInsensitiveStringMap): StructType = {
    IoTDBUtils.getSchema(IoTDBOptions.fromMap(caseInsensitiveStringMap.asCaseSensitiveMap().asScala.toMap))
  }

  override def getTable(structType: StructType, transforms: Array[Transform], map: util.Map[String, String]): Table = {
    val db = map.get(IoTDBOptions.IOTDB_DATABASE)
    val table = map.get(IoTDBOptions.IOTDB_TABLE)
    new IoTDBTable(Identifier.of(Array[String](db), table), structType, IoTDBOptions.fromMap(map.asScala.toMap))
  }
}
