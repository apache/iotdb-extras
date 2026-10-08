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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.relational.flink.utils;

import java.sql.Timestamp;
import java.util.Locale;

/**
 * Timestamp precision used by the target IoTDB server.
 *
 * <p>The values mirror IoTDB's {@code timestamp_precision}: {@code ms}, {@code us} and {@code ns}.
 * The connector converts between Flink's epoch-millisecond based timestamps and the precision of
 * the IoTDB server with the helpers below.
 */
public enum TimestampPrecision {
  MS("ms", 1L, 3),
  US("us", 1_000L, 6),
  NS("ns", 1_000_000L, 9);

  private final String value;
  private final long timeFactor;
  private final int flinkPrecision;

  TimestampPrecision(String value, long timeFactor, int flinkPrecision) {
    this.value = value;
    this.timeFactor = timeFactor;
    this.flinkPrecision = flinkPrecision;
  }

  /** @return the option value, e.g. {@code ms}. */
  public String getValue() {
    return value;
  }

  /** @return the number of units in one millisecond. */
  public long getTimeFactor() {
    return timeFactor;
  }

  /** @return the equivalent Flink {@code TIMESTAMP(p)} precision. */
  public int getFlinkPrecision() {
    return flinkPrecision;
  }

  /**
   * Converts an epoch-millisecond value (with its sub-millisecond nanoseconds) into this
   * precision's units.
   *
   * @param epochMillis milliseconds since the epoch
   * @param nanoOfMilli nanoseconds within the millisecond, in {@code [0, 1_000_000)}
   * @return the value in this precision's units
   */
  public long toUnits(long epochMillis, int nanoOfMilli) {
    long nanoPerUnit = 1_000_000L / timeFactor;
    return epochMillis * timeFactor + nanoOfMilli / nanoPerUnit;
  }

  /**
   * Converts a value in this precision's units into epoch milliseconds, truncating the
   * sub-millisecond part.
   *
   * @param raw value in this precision's units
   * @return milliseconds since the epoch
   */
  public long toEpochMillis(long raw) {
    return Math.floorDiv(raw, timeFactor);
  }

  /**
   * Converts a value in this precision's units into a {@link Timestamp}, keeping the
   * sub-millisecond part.
   *
   * @param raw value in this precision's units
   * @return the corresponding timestamp
   */
  public Timestamp toTimestamp(long raw) {
    long millis = Math.floorDiv(raw, timeFactor);
    long sub = Math.floorMod(raw, timeFactor);
    long nanoPerUnit = 1_000_000L / timeFactor;
    Timestamp timestamp = new Timestamp(millis);
    timestamp.setNanos((int) (sub * nanoPerUnit));
    return timestamp;
  }

  /**
   * Parses a precision value, e.g. {@code ms}, case-insensitively.
   *
   * @param value option value
   * @return the matching precision
   * @throws IllegalArgumentException when the value is {@code null} or unsupported
   */
  public static TimestampPrecision fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Timestamp precision must not be null.");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (TimestampPrecision precision : values()) {
      if (precision.value.equals(normalized)) {
        return precision;
      }
    }
    throw new IllegalArgumentException("Unsupported timestamp precision: " + value);
  }
}
