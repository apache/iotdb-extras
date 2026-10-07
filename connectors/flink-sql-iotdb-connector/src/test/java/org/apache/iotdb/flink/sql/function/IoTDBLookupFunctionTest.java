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

package org.apache.iotdb.flink.sql.function;

import org.apache.iotdb.flink.sql.common.Options;
import org.apache.iotdb.flink.sql.wrapper.SchemaWrapper;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.session.Session;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.data.RowData;
import org.apache.flink.util.Collector;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IoTDBLookupFunctionTest {

  private IoTDBLookupFunction function;
  private Session session;
  private final List<RowData> collected = new ArrayList<>();

  @Before
  public void setUp() throws Exception {
    Configuration options = new Configuration();
    options.set(Options.SQL, "select ** from root.sg.d1");
    TableSchema schema =
        TableSchema.builder()
            .field("Time_", DataTypes.BIGINT())
            .field("root.sg.d1.s0", DataTypes.FLOAT())
            .field("root.sg.d1.s1", DataTypes.FLOAT())
            .build();
    function = new IoTDBLookupFunction(options, new SchemaWrapper(schema));

    session = mock(Session.class);
    java.lang.reflect.Field sessionField = IoTDBLookupFunction.class.getDeclaredField("session");
    sessionField.setAccessible(true);
    sessionField.set(function, session);

    function.setCollector(
        new Collector<RowData>() {
          @Override
          public void collect(RowData record) {
            collected.add(record);
          }

          @Override
          public void close() {}
        });
  }

  private void serverReturns(long time, Field... fields) throws Exception {
    SessionDataSet dataSet = mock(SessionDataSet.class);
    when(dataSet.getColumnNames())
        .thenReturn(new ArrayList<>(Arrays.asList("Time", "root.sg.d1.s0", "root.sg.d1.s1")));
    when(dataSet.next()).thenReturn(new RowRecord(time, new ArrayList<>(Arrays.asList(fields))));
    when(session.executeQueryStatement(anyString())).thenReturn(dataSet);
  }

  private static Field floatField(float value) {
    Field field = new Field(TSDataType.FLOAT);
    field.setFloatV(value);
    return field;
  }

  @Test
  public void nullFieldInMatchedRowIsReturnedAsNull() throws Exception {
    // The series root.sg.d1.s1 has no value at time 3, so the server returns a null field for it.
    serverReturns(3L, floatField(23.25f), new Field(null));

    function.eval(3L);

    assertEquals(1, collected.size());
    RowData row = collected.get(0);
    assertEquals(3L, row.getLong(0));
    assertEquals(23.25f, row.getFloat(1), 0f);
    assertTrue(row.isNullAt(2));
  }

  @Test
  public void missingTimestampReturnsNoRow() throws Exception {
    // Nothing is stored at time 6, so the lookup must not emit a row; otherwise an inner join
    // would report a match for a key that does not exist.
    SessionDataSet dataSet = mock(SessionDataSet.class);
    when(dataSet.getColumnNames())
        .thenReturn(new ArrayList<>(Arrays.asList("Time", "root.sg.d1.s0", "root.sg.d1.s1")));
    when(dataSet.next()).thenReturn(null);
    when(session.executeQueryStatement(anyString())).thenReturn(dataSet);

    function.eval(6L);

    assertTrue(collected.isEmpty());
  }

  @Test
  public void fullyPopulatedRowIsReturnedUnchanged() throws Exception {
    serverReturns(4L, floatField(24.25f), floatField(34.75f));

    function.eval(4L);

    assertEquals(1, collected.size());
    RowData row = collected.get(0);
    assertEquals(4L, row.getLong(0));
    assertEquals(24.25f, row.getFloat(1), 0f);
    assertFalse(row.isNullAt(2));
    assertEquals(34.75f, row.getFloat(2), 0f);
  }
}
