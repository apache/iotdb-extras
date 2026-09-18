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

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.types.RowKind;
import org.apache.tsfile.utils.Binary;
import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RowDataSinkDataConverterTest {

  private final RowDataSinkDataConverter converter = new RowDataSinkDataConverter();

  @Test
  public void testYieldsOneRowPerInput() throws IOException {
    try (SinkDataConverter.Iterator iterator = converter.getIterator(rowData())) {
      assertTrue(iterator.next());
      assertFalse(iterator.next());
    }

    try (SinkDataConverter.Iterator iterator = converter.getIterator(rowData())) {
      assertTrue(iterator.next());
    }
  }

  @Test
  public void testGetters() throws IOException {
    try (SinkDataConverter.Iterator iterator = converter.getIterator(rowData())) {
      assertTrue(iterator.next());

      assertEquals(1000L, iterator.getTimestamp(0));
      assertEquals("t", iterator.getString(1));
      assertEquals(7, iterator.getInt(2));
      assertEquals(2.5d, iterator.getDouble(3), 0.0d);
      assertEquals(LocalDate.ofEpochDay(10), iterator.getDate(4));
      assertArrayEquals(new byte[] {1, 2}, binary(iterator.getBlob(5)));
    }
  }

  @Test
  public void testNullField() throws IOException {
    GenericRowData row = rowData();
    row.setField(2, null);
    try (SinkDataConverter.Iterator iterator = converter.getIterator(row)) {
      assertTrue(iterator.next());

      assertTrue(iterator.isNull(2));
      assertFalse(iterator.isNull(1));
    }
  }

  @Test
  public void testNullInputHasNoRows() throws IOException {
    try (SinkDataConverter.Iterator iterator = converter.getIterator(null)) {
      assertFalse(iterator.next());
    }
  }

  @Test
  public void testRejectsNonInsertRowKind() {
    GenericRowData row = rowData();
    row.setRowKind(RowKind.DELETE);

    try {
      converter.getIterator(row);
      fail("Expected an IOException for a non-INSERT record.");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("INSERT"));
    }
  }

  private static GenericRowData rowData() {
    return GenericRowData.of(
        TimestampData.fromEpochMillis(1000L),
        StringData.fromString("t"),
        7,
        2.5d,
        10,
        new byte[] {1, 2});
  }

  private static byte[] binary(Binary value) {
    return Arrays.copyOf(value.getValues(), value.getLength());
  }
}
