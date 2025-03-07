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

import scala.collection.JavaConverters.seqAsJavaListConverter

class IoTDBOptions(
                    @transient private val properties: Map[String, String])
  extends Serializable {

    val urls = properties.getOrElse(IoTDBOptions.IOTDB_URL, sys.error(s"Option '${IoTDBOptions.IOTDB_URL}' not specified")).split(",").toList.asJava

    val username = properties.getOrElse(IoTDBOptions.IOTDB_USERNAME, "root")

    val password = properties.getOrElse(IoTDBOptions.IOTDB_PASSWORD, "root")

    val database = properties.getOrElse(IoTDBOptions.IOTDB_DATABASE, sys.error(s"Option '${IoTDBOptions.IOTDB_DATABASE}' not specified"))

    val table = properties.getOrElse(IoTDBOptions.IOTDB_TABLE, sys.error(s"Option '${IoTDBOptions.IOTDB_TABLE}' not specified"))

}

object IoTDBOptions {
  val IOTDB_USERNAME = "iotdb.username"
  val IOTDB_PASSWORD = "iotdb.password"
  val IOTDB_URL = "iotdb.url"
  val IOTDB_DATABASE = "iotdb.database"
  val IOTDB_TABLE = "iotdb.table"

  def fromMap(sparkMap: Map[String, String]): IoTDBOptions = {
    new IoTDBOptions(sparkMap.map { case (k, v) => (k.toLowerCase, v) })
  }
}