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

package org.apache.iotdb.relational.flink.source.lookup;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.TimestampType;

import java.time.LocalDate;

/**
 * Renders a runtime {@link RowData} field as an IoTDB SQL literal, used to build lookup queries.
 *
 * <p>Returns {@code null} when the value is {@code null} or cannot be represented in IoTDB SQL.
 */
public final class IoTDBRuntimeLiteralUtils {

  private IoTDBRuntimeLiteralUtils() {}

  public static String render(RowData row, int position, DataType dataType) {
    if (row == null || row.isNullAt(position) || dataType == null) {
      return null;
    }

    LogicalTypeRoot typeRoot = dataType.getLogicalType().getTypeRoot();
    switch (typeRoot) {
      case BOOLEAN:
        return Boolean.toString(row.getBoolean(position));
      case TINYINT:
        return Byte.toString(row.getByte(position));
      case SMALLINT:
        return Short.toString(row.getShort(position));
      case INTEGER:
        return Integer.toString(row.getInt(position));
      case BIGINT:
        return Long.toString(row.getLong(position));
      case FLOAT:
        float floatValue = row.getFloat(position);
        return isFinite(floatValue) ? Float.toString(floatValue) : null;
      case DOUBLE:
        double doubleValue = row.getDouble(position);
        return isFinite(doubleValue) ? Double.toString(doubleValue) : null;
      case CHAR:
      case VARCHAR:
        return quoteString(row.getString(position).toString());
      case BINARY:
      case VARBINARY:
        byte[] bytes = row.getBinary(position);
        return bytes == null ? null : formatBinary(bytes);
      case DATE:
        return "CAST('" + LocalDate.ofEpochDay(row.getInt(position)) + "' AS DATE)";
      case TIMESTAMP_WITHOUT_TIME_ZONE:
        return "CAST('"
            + getTimestamp(row, position, dataType).toLocalDateTime()
            + "' AS TIMESTAMP)";
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        return "CAST('" + getTimestamp(row, position, dataType).toInstant() + "' AS TIMESTAMP)";
      default:
        return null;
    }
  }

  private static TimestampData getTimestamp(RowData row, int position, DataType dataType) {
    int precision = ((TimestampType) dataType.getLogicalType()).getPrecision();
    return row.getTimestamp(position, precision);
  }

  private static boolean isFinite(float value) {
    return !Float.isNaN(value) && !Float.isInfinite(value);
  }

  private static boolean isFinite(double value) {
    return !Double.isNaN(value) && !Double.isInfinite(value);
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
