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

package org.apache.iotdb.relational.flink.sink;

import org.apache.flink.table.data.RowData;
import org.apache.flink.types.RowKind;
import org.apache.tsfile.utils.Binary;

import java.io.IOException;
import java.time.LocalDate;

/**
 * {@link SinkDataConverter} over Flink {@link RowData}.
 *
 * <p>Each input element is a single row, so the returned iterator yields at most one row. Column
 * indexes map directly to the RowData field positions.
 */
public class RowDataSinkDataConverter implements SinkDataConverter<RowData> {

  private static final long serialVersionUID = 1L;

  @Override
  public Iterator getIterator(RowData record) throws IOException {
    return new RowDataIterator(record);
  }

  private static class RowDataIterator implements SinkDataConverter.Iterator {

    private final RowData current;
    private boolean consumed;

    private RowDataIterator(RowData record) throws IOException {
      if (record != null && record.getRowKind() != RowKind.INSERT) {
        throw new IOException(
            "The IoTDB table sink only accepts INSERT records, but got "
                + record.getRowKind()
                + ".");
      }
      this.current = record;
    }

    @Override
    public boolean next() {
      if (current == null || consumed) {
        return false;
      }
      consumed = true;
      return true;
    }

    @Override
    public boolean isNull(int columnIndex) {
      return current.isNullAt(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) {
      return current.getBoolean(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) {
      return current.getInt(columnIndex);
    }

    @Override
    public long getLong(int columnIndex) {
      return current.getLong(columnIndex);
    }

    @Override
    public float getFloat(int columnIndex) {
      return current.getFloat(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) {
      return current.getDouble(columnIndex);
    }

    @Override
    public String getString(int columnIndex) {
      return current.getString(columnIndex).toString();
    }

    @Override
    public Binary getBlob(int columnIndex) {
      return new Binary(current.getBinary(columnIndex));
    }

    @Override
    public LocalDate getDate(int columnIndex) {
      return LocalDate.ofEpochDay(current.getInt(columnIndex));
    }

    @Override
    public long getTimestamp(int columnIndex) {
      return current.getTimestamp(columnIndex, 3).getMillisecond();
    }
  }
}
