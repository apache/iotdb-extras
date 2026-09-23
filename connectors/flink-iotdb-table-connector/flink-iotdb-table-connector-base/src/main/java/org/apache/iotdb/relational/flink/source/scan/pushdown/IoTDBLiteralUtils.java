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

package org.apache.iotdb.relational.flink.source.scan.pushdown;

import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.types.logical.LogicalTypeRoot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/** Renders Flink literal expressions as IoTDB SQL literals. */
public final class IoTDBLiteralUtils {

  private IoTDBLiteralUtils() {}

  /**
   * Renders a literal for pushdown. Returns {@code null} if the literal type or value cannot be
   * represented in IoTDB SQL.
   */
  public static String render(ValueLiteralExpression literal) {
    if (literal == null || literal.isNull()) {
      return null;
    }

    try {
      LogicalTypeRoot typeRoot = literal.getOutputDataType().getLogicalType().getTypeRoot();
      switch (typeRoot) {
        case BOOLEAN:
          return literal.getValueAs(Boolean.class).map(String::valueOf).orElse(null);
        case TINYINT:
          return value(literal.getValueAs(Byte.class));
        case SMALLINT:
          return value(literal.getValueAs(Short.class));
        case INTEGER:
          return value(literal.getValueAs(Integer.class));
        case BIGINT:
          return value(literal.getValueAs(Long.class));
        case FLOAT:
          return literal
              .getValueAs(Float.class)
              .filter(value -> !value.isNaN() && !value.isInfinite())
              .map(String::valueOf)
              .orElse(null);
        case DOUBLE:
          return literal
              .getValueAs(Double.class)
              .filter(value -> !value.isNaN() && !value.isInfinite())
              .map(String::valueOf)
              .orElse(null);
        case CHAR:
        case VARCHAR:
          return literal.getValueAs(String.class).map(IoTDBLiteralUtils::quoteString).orElse(null);
        case BINARY:
        case VARBINARY:
          return literal.getValueAs(byte[].class).map(IoTDBLiteralUtils::formatBinary).orElse(null);
        case DATE:
          return literal
              .getValueAs(LocalDate.class)
              .map(value -> "CAST('" + value + "' AS DATE)")
              .orElse(null);
        case TIMESTAMP_WITHOUT_TIME_ZONE:
          return literal
              .getValueAs(LocalDateTime.class)
              .map(value -> "CAST('" + value + "' AS TIMESTAMP)")
              .orElse(null);
        case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
          return literal
              .getValueAs(Instant.class)
              .map(value -> "CAST('" + value + "' AS TIMESTAMP)")
              .orElse(null);
        default:
          return null;
      }
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String value(Optional<?> value) {
    return value.map(Object::toString).orElse(null);
  }

  private static String quoteString(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String formatBinary(byte[] value) {
    StringBuilder builder = new StringBuilder(value.length * 2 + 3);
    builder.append("X'");
    for (byte b : value) {
      builder.append(String.format("%02X", b));
    }
    builder.append("'");
    return builder.toString();
  }
}
