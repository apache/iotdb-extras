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

package org.apache.iotdb.relational.flink.source.scan.split;

import org.apache.flink.api.connector.source.SourceSplit;

import java.io.Serializable;
import java.util.Objects;

/** Read split for the IoTDB relational table source. */
public class IoTDBSourceSplit implements SourceSplit, Serializable {

  private static final long serialVersionUID = 1L;

  private final String splitId;
  private final String database;
  private final String table;
  private final String sql;

  public IoTDBSourceSplit(String splitId, String database, String table, String sql) {
    this.splitId = splitId;
    this.database = database;
    this.table = table;
    this.sql = sql;
  }

  @Override
  public String splitId() {
    return splitId;
  }

  public String getDatabase() {
    return database;
  }

  public String getTable() {
    return table;
  }

  public String getSql() {
    return sql;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof IoTDBSourceSplit)) {
      return false;
    }
    IoTDBSourceSplit that = (IoTDBSourceSplit) o;
    return Objects.equals(splitId, that.splitId)
        && Objects.equals(database, that.database)
        && Objects.equals(table, that.table)
        && Objects.equals(sql, that.sql);
  }

  @Override
  public int hashCode() {
    return Objects.hash(splitId, database, table, sql);
  }
}
