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

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class AggregationParams {
  private static final Map<String, String> TZ_LINKS =
      Map.of(
          "EST", "America/New_York",
          "GMT+0", "GMT",
          "GMT-0", "GMT",
          "HST", "US/Hawaii",
          "MST", "America/Phoenix",
          "ROC", "Asia/Taipei");

  private final Aggregation aggregation;
  private final IntervalType intervalType;
  private final ZoneId tzId;
  private final long interval;

  public AggregationParams(
      Aggregation aggregation, IntervalType intervalType, ZoneId tzId, long interval) {
    this.aggregation = Objects.requireNonNull(aggregation, "aggregation");
    this.intervalType = intervalType;
    this.tzId = tzId;
    this.interval = interval;
  }

  public static AggregationParams none() {
    return new AggregationParams(Aggregation.NONE, null, null, 0L);
  }

  public static AggregationParams milliseconds(
      Aggregation aggregationType, long aggregationIntervalMs) {
    return new AggregationParams(
        aggregationType, IntervalType.MILLISECONDS, null, aggregationIntervalMs);
  }

  public static AggregationParams calendar(
      Aggregation aggregationType, IntervalType intervalType, String tzIdStr) {
    return calendar(aggregationType, intervalType, getZoneId(tzIdStr));
  }

  public static AggregationParams calendar(
      Aggregation aggregationType, IntervalType intervalType, ZoneId tzId) {
    return new AggregationParams(aggregationType, intervalType, tzId, 0L);
  }

  public static AggregationParams of(
      Aggregation aggregation, IntervalType intervalType, ZoneId tzId, long interval) {
    return new AggregationParams(aggregation, intervalType, tzId, interval);
  }

  public Aggregation getAggregation() {
    return aggregation;
  }

  public IntervalType getIntervalType() {
    return intervalType;
  }

  public ZoneId getTzId() {
    return tzId;
  }

  public long getInterval() {
    // Matches real ThingsBoard v4.3.1.2 AggregationParams.getInterval(): a null IntervalType
    // returns 0L. Real TB never pairs a null IntervalType with a non-NONE aggregation, so the
    // DAO's MILLISECONDS-path positive-interval guard is never legitimately reached for a null
    // type; the real MILLISECONDS path always carries IntervalType.MILLISECONDS with a positive
    // interval (see AggregationParams.milliseconds).
    if (intervalType == null) {
      return 0L;
    }
    return switch (intervalType) {
      case WEEK, WEEK_ISO -> TimeUnit.DAYS.toMillis(7);
      case MONTH -> TimeUnit.DAYS.toMillis(30);
      case QUARTER -> TimeUnit.DAYS.toMillis(90);
      default -> interval;
    };
  }

  private static ZoneId getZoneId(String tzIdStr) {
    if (tzIdStr == null || tzIdStr.isEmpty()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(tzIdStr, TZ_LINKS);
    } catch (DateTimeException e) {
      return ZoneId.systemDefault();
    }
  }
}
