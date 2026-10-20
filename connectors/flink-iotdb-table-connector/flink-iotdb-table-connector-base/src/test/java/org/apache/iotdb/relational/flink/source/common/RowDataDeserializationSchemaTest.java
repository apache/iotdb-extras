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

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.RowData;
import org.junit.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class RowDataDeserializationSchemaTest {

  @Test
  public void testTimestampDoesNotDependOnDefaultTimeZone() throws Exception {
    RowDataDeserializationSchema schema =
        new RowDataDeserializationSchema(
            DataTypes.ROW(DataTypes.FIELD("time", DataTypes.TIMESTAMP(3))));
    TimeZone defaultZone = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
      RowData row = schema.deserialize(new SingleTimestampIterator(new Timestamp(1000L)));
      assertEquals(1000L, row.getTimestamp(0, 3).getMillisecond());
    } finally {
      TimeZone.setDefault(defaultZone);
    }
  }

  /** One row with a single TIMESTAMP column named {@code time}. */
  private static class SingleTimestampIterator implements IoTDBDataIterator {
    private final Timestamp timestamp;

    SingleTimestampIterator(Timestamp timestamp) {
      this.timestamp = timestamp;
    }

    @Override
    public boolean next() {
      return true;
    }

    @Override
    public List<String> getColumnNames() {
      return Collections.singletonList("time");
    }

    @Override
    public boolean isNull(int columnIndex) {
      return false;
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) {
      return timestamp;
    }

    @Override
    public boolean getBoolean(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getInt(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getLong(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public float getFloat(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public double getDouble(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getString(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getBinary(int columnIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    public LocalDate getDate(int columnIndex) {
      throw new UnsupportedOperationException();
    }
  }
}
