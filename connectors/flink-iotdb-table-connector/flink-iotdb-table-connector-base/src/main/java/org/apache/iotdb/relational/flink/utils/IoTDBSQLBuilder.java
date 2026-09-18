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

package org.apache.iotdb.relational.flink.utils;

import org.apache.flink.table.types.DataType;

import java.util.List;

/** Builds reusable IoTDB SQL statements for the relational table connector. */
public final class IoTDBSQLBuilder {

  private IoTDBSQLBuilder() {}

  /**
   * Builds a bounded table-model scan query.
   *
   * @param table IoTDB table name
   * @param rowDataType projected Flink row type whose field names define the SELECT list
   * @param filterQueries already-rendered IoTDB predicate fragments
   * @param limit maximum number of rows, or a negative value for no limit
   * @return IoTDB SELECT SQL
   */
  public static String buildSelectQuery(
      String table, DataType rowDataType, List<String> filterQueries, long limit) {
    List<String> fieldNames = DataType.getFieldNames(rowDataType);
    if (fieldNames.isEmpty()) {
      throw new IllegalArgumentException("IoTDB source requires at least one selected column.");
    }

    StringBuilder columns = new StringBuilder();
    for (String fieldName : fieldNames) {
      if (columns.length() > 0) {
        columns.append(", ");
      }
      columns.append(IoTDBIdentifierUtils.quoteIdentifier(fieldName));
    }

    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(columns)
            .append(" FROM ")
            .append(IoTDBIdentifierUtils.quoteIdentifier(table));
    if (filterQueries != null && !filterQueries.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", filterQueries));
    }
    if (limit >= 0) {
      sql.append(" LIMIT ").append(limit);
    }
    return sql.toString();
  }
}
