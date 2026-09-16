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

package org.apache.iotdb.relational.flink.source.deserializer;

import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.rpc.StatementExecutionException;

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.types.RowKind;
import org.apache.tsfile.utils.Binary;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Table API deserializer that converts the current {@link SessionDataSet.DataIterator} row into
 * {@link RowData}.
 */
public class RowDataDeserializationSchema implements IoTDBDeserializationSchema<RowData> {

  private static final long serialVersionUID = 1L;

  private final DataType rowDataType;
  private final List<DataType> fieldDataTypes;

  public RowDataDeserializationSchema(DataType rowDataType) {
    if (rowDataType.getLogicalType().getTypeRoot() != LogicalTypeRoot.ROW) {
      throw new IllegalArgumentException("RowDataDeserializationSchema requires a ROW data type.");
    }
    this.rowDataType = rowDataType;
    this.fieldDataTypes = DataType.getFieldDataTypes(rowDataType);
  }

  @Override
  public RowData deserialize(SessionDataSet.DataIterator iterator) throws IOException {
    GenericRowData row = new GenericRowData(RowKind.INSERT, fieldDataTypes.size());
    for (int i = 0; i < fieldDataTypes.size(); i++) {
      row.setField(i, readField(iterator, i + 1, fieldDataTypes.get(i)));
    }
    return row;
  }

  public DataType getRowDataType() {
    return rowDataType;
  }

  private static Object readField(
      SessionDataSet.DataIterator iterator, int columnIndex, DataType dataType) throws IOException {
    try {
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
          Binary binary = iterator.getBlob(columnIndex);
          return binary == null ? null : Arrays.copyOf(binary.getValues(), binary.getLength());
        case DATE:
          return (int) iterator.getDate(columnIndex).toEpochDay();
        case TIMESTAMP_WITHOUT_TIME_ZONE:
        case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
          return TimestampData.fromTimestamp(iterator.getTimestamp(columnIndex));
        default:
          throw new IOException(
              "Unsupported Flink type at column " + columnIndex + ": " + dataType.getLogicalType());
      }
    } catch (StatementExecutionException e) {
      throw new IOException(
          "Failed to read IoTDB column " + columnIndex + " as " + dataType.getLogicalType(), e);
    }
  }
}
