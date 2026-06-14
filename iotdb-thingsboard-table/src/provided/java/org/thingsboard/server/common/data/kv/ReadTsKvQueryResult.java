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

// Compile-only ThingsBoard stub (Strategy F). Verified against ThingsBoard v4.3.1.2
// (commit c37fb509) on 2026-06-12.
package org.thingsboard.server.common.data.kv;

import org.thingsboard.server.common.data.query.TsValue;

import java.util.ArrayList;
import java.util.List;

public class ReadTsKvQueryResult {
  private final int queryId;
  private final List<TsKvEntry> data;
  private final long lastEntryTs;

  public ReadTsKvQueryResult(int queryId, List<TsKvEntry> data, long lastEntryTs) {
    this.queryId = queryId;
    this.data = data;
    this.lastEntryTs = lastEntryTs;
  }

  public int getQueryId() {
    return queryId;
  }

  public List<TsKvEntry> getData() {
    return data;
  }

  public long getLastEntryTs() {
    return lastEntryTs;
  }

  public TsValue[] toTsValues() {
    if (data != null && !data.isEmpty()) {
      List<TsValue> queryValues = new ArrayList<>();
      for (TsKvEntry entry : data) {
        queryValues.add(entry.toTsValue());
      }
      return queryValues.toArray(new TsValue[0]);
    }
    return new TsValue[0];
  }

  public TsValue toTsValue(ReadTsKvQuery query) {
    if (data == null || data.isEmpty()) {
      if (Aggregation.SUM.equals(query.getAggregation())
          || Aggregation.COUNT.equals(query.getAggregation())) {
        long ts = query.getStartTs() + (query.getEndTs() - query.getStartTs()) / 2;
        return new TsValue(ts, "0");
      }
      return TsValue.EMPTY;
    }
    if (data.size() > 1) {
      throw new RuntimeException("Query Result has multiple data points!");
    }
    return data.get(0).toTsValue();
  }
}
