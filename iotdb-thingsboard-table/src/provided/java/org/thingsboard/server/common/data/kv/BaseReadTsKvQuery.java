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

import java.time.ZoneId;

public class BaseReadTsKvQuery extends BaseTsKvQuery implements ReadTsKvQuery {
  private final AggregationParams aggParameters;
  private final int limit;
  private final String order;

  public BaseReadTsKvQuery(
      String key, long startTs, long endTs, long interval, int limit, Aggregation aggregation) {
    this(key, startTs, endTs, interval, limit, aggregation, "DESC");
  }

  public BaseReadTsKvQuery(
      String key,
      long startTs,
      long endTs,
      long interval,
      int limit,
      Aggregation aggregation,
      String descOrder) {
    this(
        key,
        startTs,
        endTs,
        AggregationParams.of(
            aggregation, IntervalType.MILLISECONDS, ZoneId.systemDefault(), interval),
        limit,
        descOrder);
  }

  public BaseReadTsKvQuery(
      String key, long startTs, long endTs, AggregationParams parameters, int limit) {
    this(key, startTs, endTs, parameters, limit, "DESC");
  }

  public BaseReadTsKvQuery(
      String key,
      long startTs,
      long endTs,
      AggregationParams parameters,
      int limit,
      String order) {
    super(key, startTs, endTs);
    this.aggParameters = parameters;
    this.limit = limit;
    this.order = order;
  }

  public BaseReadTsKvQuery(String key, long startTs, long endTs) {
    this(key, startTs, endTs, AggregationParams.milliseconds(Aggregation.AVG, endTs - startTs), 1);
  }

  public BaseReadTsKvQuery(String key, long startTs, long endTs, int limit, String order) {
    this(key, startTs, endTs, AggregationParams.none(), limit, order);
  }

  public BaseReadTsKvQuery(ReadTsKvQuery query, long startTs, long endTs) {
    super(query.getId(), query.getKey(), startTs, endTs);
    this.aggParameters = query.getAggParameters();
    this.limit = query.getLimit();
    this.order = query.getOrder();
  }

  @Override
  public AggregationParams getAggParameters() {
    return aggParameters;
  }

  @Override
  public int getLimit() {
    return limit;
  }

  @Override
  public String getOrder() {
    return order;
  }
}
