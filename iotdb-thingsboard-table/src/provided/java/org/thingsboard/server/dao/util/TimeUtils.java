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
// (tag commit c37fb509).
package org.thingsboard.server.dao.util;

import org.thingsboard.server.common.data.kv.IntervalType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;

/**
 * Strategy F provided-surface stub mirroring ThingsBoard {@code
 * org.thingsboard.server.dao.util.TimeUtils}. Provided on the compile classpath only and excluded
 * from the published jar, exactly like the other {@code src/provided} ThingsBoard types.
 *
 * <p>{@link #calculateIntervalEnd(long, IntervalType, ZoneId)} is the calendar-bucket boundary
 * primitive that {@code AbstractChunkedAggregationTimeseriesDao.findAllAsync} uses for every
 * non-{@code MILLISECONDS} {@link IntervalType}: it advances {@code startTs} to the start of the
 * next calendar unit (next week, next 1st-of-month, next quarter start) in {@code tzId}. The IoTDB
 * Table Mode calendar aggregation path ({@code IoTDBTableTimeseriesDao}) calls this so its bucket
 * boundaries are identical to ThingsBoard's, because IoTDB 2.0.8's native {@code date_bin} calendar
 * primitive anchors on the origin's day-of-month and exposes no timezone argument and therefore
 * cannot reproduce ThingsBoard's timezone-aware, calendar-start-aligned boundaries.
 */
public final class TimeUtils {

  private TimeUtils() {}

  public static long calculateIntervalEnd(long startTs, IntervalType intervalType, ZoneId tzId) {
    var startTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startTs), tzId);
    switch (intervalType) {
      case WEEK:
        return startTime
            .truncatedTo(ChronoUnit.DAYS)
            .with(WeekFields.SUNDAY_START.dayOfWeek(), 1)
            .plusDays(7)
            .toInstant()
            .toEpochMilli();
      case WEEK_ISO:
        return startTime
            .truncatedTo(ChronoUnit.DAYS)
            .with(WeekFields.ISO.dayOfWeek(), 1)
            .plusDays(7)
            .toInstant()
            .toEpochMilli();
      case MONTH:
        return startTime
            .truncatedTo(ChronoUnit.DAYS)
            .withDayOfMonth(1)
            .plusMonths(1)
            .toInstant()
            .toEpochMilli();
      case QUARTER:
        return startTime
            .truncatedTo(ChronoUnit.DAYS)
            .with(IsoFields.DAY_OF_QUARTER, 1)
            .plusMonths(3)
            .toInstant()
            .toEpochMilli();
      default:
        throw new RuntimeException("Not supported!");
    }
  }

  public static ZonedDateTime toZonedDateTime(long ts, ZoneId zoneId) {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), zoneId);
  }
}
