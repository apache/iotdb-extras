/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.extras.thingsboard.table;

import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.StatementExecutionException;

import lombok.extern.slf4j.Slf4j;

/**
 * Base class for IoTDB Table Mode DAOs; not a Spring bean itself. Concrete DAOs declare
 * {@code @Repository} and the activation conditional. Holds the shared {@code ITableSessionPool}
 * (wired via constructor injection) and provides type-mapping helpers used by concrete DAOs
 * (TimeseriesDao, LatestDao, AttributesDao, LabelDao).
 *
 * <p>Strategy F keeps this class free of ThingsBoard imports and interface clauses; concrete DAOs
 * bind to the ThingsBoard SPI types directly.
 */
@Slf4j
public class IoTDBTableBaseDao {
  protected final ITableSessionPool tableSessionPool;

  public IoTDBTableBaseDao(ITableSessionPool tableSessionPool) {
    this.tableSessionPool = tableSessionPool;
  }

  /**
   * Maps a single IoTDB Table Mode telemetry row's 5 typed FIELD columns to a TypedKvValue. Exactly
   * one typed value column may be non-null because the telemetry schema stores exactly one typed
   * value per row. Throws IllegalStateException if more than one is non-null (fail-fast on schema
   * invariant violation). Returns TypedKvValue.empty() if all 5 are null (caller decides how to
   * handle). The caller must call row.next() and receive true before invoking this method so the
   * iterator is positioned on a row.
   */
  public TypedKvValue getEntry(SessionDataSet.DataIterator row) throws StatementExecutionException {
    boolean hasBoolean = !row.isNull("bool_v");
    boolean hasLong = !row.isNull("long_v");
    boolean hasDouble = !row.isNull("double_v");
    boolean hasString = !row.isNull("str_v");
    boolean hasJson = !row.isNull("json_v");

    int valueCount = 0;
    valueCount += hasBoolean ? 1 : 0;
    valueCount += hasLong ? 1 : 0;
    valueCount += hasDouble ? 1 : 0;
    valueCount += hasString ? 1 : 0;
    valueCount += hasJson ? 1 : 0;

    if (valueCount == 0) {
      return TypedKvValue.empty();
    }
    if (valueCount > 1) {
      throw new IllegalStateException(
          "IoTDB telemetry row has "
              + valueCount
              + " typed value columns set; the telemetry schema stores exactly one typed value "
              + "column per row. "
              + "Possible same-timestamp type-change bug or stale data.");
    }
    if (hasBoolean) {
      return TypedKvValue.ofBoolean(row.getBoolean("bool_v"));
    }
    if (hasLong) {
      return TypedKvValue.ofLong(row.getLong("long_v"));
    }
    if (hasDouble) {
      return TypedKvValue.ofDouble(row.getDouble("double_v"));
    }
    if (hasString) {
      return TypedKvValue.ofString(row.getString("str_v"));
    }
    return TypedKvValue.ofJson(row.getString("json_v"));
  }
}
