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

import org.apache.iotdb.spark.table.db.IoTDBOptions
import org.apache.spark.sql.connector.write.{Write, WriteBuilder}
import org.apache.spark.sql.types.StructType

class IoTDBWriteBuilder(options: IoTDBOptions, writeSchema: StructType, tableSchema: StructType) extends WriteBuilder {
  override def build(): Write = new IoTDBWrite(options, writeSchema, tableSchema)
}
