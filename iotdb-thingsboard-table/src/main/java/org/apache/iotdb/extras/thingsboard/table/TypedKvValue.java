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

import java.util.Objects;

/**
 * A single typed value mapped from one IoTDB Table Mode telemetry row's 5 typed FIELD columns
 * (bool_v, long_v, double_v, str_v, json_v). Exactly one field is non-null per valid row.
 */
public record TypedKvValue(
    Boolean booleanValue,
    Long longValue,
    Double doubleValue,
    String stringValue,
    String jsonValue) {

  public static TypedKvValue ofBoolean(boolean v) {
    return new TypedKvValue(v, null, null, null, null);
  }

  public static TypedKvValue ofLong(long v) {
    return new TypedKvValue(null, v, null, null, null);
  }

  public static TypedKvValue ofDouble(double v) {
    return new TypedKvValue(null, null, v, null, null);
  }

  public static TypedKvValue ofString(String v) {
    return new TypedKvValue(null, null, null, Objects.requireNonNull(v, "stringValue"), null);
  }

  public static TypedKvValue ofJson(String v) {
    return new TypedKvValue(null, null, null, null, Objects.requireNonNull(v, "jsonValue"));
  }

  public static TypedKvValue empty() {
    return new TypedKvValue(null, null, null, null, null);
  }

  public boolean hasValue() {
    return valueCount() > 0;
  }

  public Object value() {
    int count = valueCount();
    if (count == 0) {
      return null;
    }
    if (count > 1) {
      throw new IllegalStateException("TypedKvValue contains multiple values");
    }
    if (booleanValue != null) {
      return booleanValue;
    }
    if (longValue != null) {
      return longValue;
    }
    if (doubleValue != null) {
      return doubleValue;
    }
    if (stringValue != null) {
      return stringValue;
    }
    return jsonValue;
  }

  private int valueCount() {
    int count = 0;
    count += booleanValue == null ? 0 : 1;
    count += longValue == null ? 0 : 1;
    count += doubleValue == null ? 0 : 1;
    count += stringValue == null ? 0 : 1;
    count += jsonValue == null ? 0 : 1;
    return count;
  }
}
