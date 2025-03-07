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

import org.apache.iotdb.spark.table.db.IoTDBOptions
import org.apache.spark.sql.connector.expressions.filter.Predicate
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.types.StructType

import java.util

/**
 * IoTDBScanBuilder is responsible for constructing an IoTDBScan with
 * support for predicate push-down, column pruning, offset, and limit.
 *
 * @param options The IoTDB connection and query options.
 * @param schema The full schema of the table.
 */
class IoTDBScanBuilder(options: IoTDBOptions, schema: StructType) extends ScanBuilder
    with SupportsPushDownRequiredColumns
    with SupportsPushDownV2Filters
    with SupportsPushDownOffset
    with SupportsPushDownLimit {

  private var supportedFilters: Array[Predicate] = Array.empty
  private var pushDownFilterStrings: Array[String] = Array.empty
  private var requiredColumns: StructType = schema
  private var pushDownOffset: Int = -1
  private var pushDownLimit: Int = -1

  override def build(): Scan = {
    new IoTDBScan(options, requiredColumns, pushDownFilterStrings, pushDownOffset, pushDownLimit)
  }

  override def pruneColumns(requiredSchema: StructType): Unit = {
    if (requiredSchema.nonEmpty) {
      val fields = schema.fields.filter(
        field => requiredSchema.fieldNames.contains(field.name)
      )
      if (fields.isEmpty) {
        throw new IllegalArgumentException("No required columns found")
      }
      requiredColumns = StructType(fields)
    } else {
      requiredColumns = schema
    }
  }

  override def pushOffset(offset: Int): Boolean = {
    pushDownOffset = offset
    true
  }

  override def pushLimit(limit: Int): Boolean = {
    pushDownLimit = limit
    true
  }

  override def pushPredicates(predicates: Array[Predicate]): Array[Predicate] = {
    val compiledPredicates = new util.ArrayList[String]()
    val builder = new IoTDBExpressionSQLBuilder
    val (supported, unsupported) = predicates.partition(predicate => {
      try {
        val sql = builder.build(predicate)
        compiledPredicates.add(sql)
        true
      } catch {
        case e: Exception => {
          System.err.println(s"Predicate push-down failed for: $predicate, reason: ${e.getMessage}")
          false
        }
      }
    })
    pushDownFilterStrings = compiledPredicates.toArray(new Array[String](compiledPredicates.size()))
    supportedFilters = supported
    unsupported
  }

  override def pushedPredicates(): Array[Predicate] = {
		supportedFilters
  }

}
