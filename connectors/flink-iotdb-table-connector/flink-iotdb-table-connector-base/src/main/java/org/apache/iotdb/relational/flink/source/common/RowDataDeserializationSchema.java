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

package org.apache.iotdb.relational.flink.source.common;

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.types.RowKind;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Table API deserializer that converts the current row of an {@link IoTDBDataIterator} into {@link
 * RowData}. Columns are matched to the declared row type by name (case-insensitive) and fall back
 * to the same positional index when no name matches.
 */
public class RowDataDeserializationSchema implements IoTDBDeserializationSchema<RowData> {

  private static final long serialVersionUID = 1L;

  private final DataType rowDataType;
  private final List<String> fieldNames;
  private final List<DataType> fieldTypes;

  private transient List<String> cachedColumnNames;
  private transient int[] cachedColumnIndexes;

  public RowDataDeserializationSchema(DataType rowDataType) {
    if (rowDataType.getLogicalType().getTypeRoot() != LogicalTypeRoot.ROW) {
      throw new IllegalArgumentException("RowDataDeserializationSchema requires a ROW data type.");
    }
    this.rowDataType = rowDataType;
    this.fieldNames = DataType.getFieldNames(rowDataType);
    this.fieldTypes = DataType.getFieldDataTypes(rowDataType);
  }

  @Override
  public RowData deserialize(IoTDBDataIterator iterator) throws IOException {
    int[] columnIndexes = resolveColumnIndexes(iterator.getColumnNames());
    GenericRowData row = new GenericRowData(RowKind.INSERT, fieldNames.size());
    for (int i = 0; i < fieldNames.size(); i++) {
      int columnIndex = columnIndexes[i];
      row.setField(i, columnIndex < 0 ? null : readField(iterator, columnIndex, fieldTypes.get(i)));
    }
    return row;
  }

  public DataType getRowDataType() {
    return rowDataType;
  }

  private int[] resolveColumnIndexes(List<String> columnNames) {
    if (cachedColumnNames != null && cachedColumnNames.equals(columnNames)) {
      return cachedColumnIndexes;
    }
    int[] indexes = new int[fieldNames.size()];
    for (int i = 0; i < fieldNames.size(); i++) {
      int found = indexOfIgnoreCase(columnNames, fieldNames.get(i));
      if (found < 0 && i < columnNames.size()) {
        found = i;
      }
      indexes[i] = found;
    }
    cachedColumnNames = columnNames;
    cachedColumnIndexes = indexes;
    return indexes;
  }

  private static int indexOfIgnoreCase(List<String> columnNames, String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    for (int i = 0; i < columnNames.size(); i++) {
      if (columnNames.get(i).toLowerCase(Locale.ROOT).equals(normalized)) {
        return i;
      }
    }
    return -1;
  }

  private static Object readField(IoTDBDataIterator iterator, int columnIndex, DataType dataType)
      throws IOException {
    if (iterator.isNull(columnIndex)) {
      return null;
    }

    LogicalTypeRoot typeRoot = dataType.getLogicalType().getTypeRoot();
    switch (typeRoot) {
      case BOOLEAN:
        return iterator.getBoolean(columnIndex);
      case TINYINT:
        return (byte) iterator.getInt(columnIndex);
      case SMALLINT:
        return (short) iterator.getInt(columnIndex);
      case INTEGER:
        return iterator.getInt(columnIndex);
      case BIGINT:
        return iterator.getLong(columnIndex);
      case FLOAT:
        return iterator.getFloat(columnIndex);
      case DOUBLE:
        return iterator.getDouble(columnIndex);
      case CHAR:
      case VARCHAR:
        return StringData.fromString(iterator.getString(columnIndex));
      case BINARY:
      case VARBINARY:
        return iterator.getBinary(columnIndex);
      case DATE:
        return (int) iterator.getDate(columnIndex).toEpochDay();
      case TIMESTAMP_WITHOUT_TIME_ZONE:
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        return TimestampData.fromTimestamp(iterator.getTimestamp(columnIndex));
      default:
        throw new IOException(
            "Unsupported Flink type at column " + columnIndex + ": " + dataType.getLogicalType());
    }
  }
}
