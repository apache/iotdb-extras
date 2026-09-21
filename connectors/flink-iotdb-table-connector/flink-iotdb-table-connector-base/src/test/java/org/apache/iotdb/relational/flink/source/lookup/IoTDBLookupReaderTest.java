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

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Unit tests for the lookup SQL rendering of {@link IoTDBLookupReader}. */
public class IoTDBLookupReaderTest {

  private static final IoTDBOptions OPTIONS =
      IoTDBOptions.builder()
          .withNodeUrls(Collections.singletonList("127.0.0.1:6667"))
          .withDatabase("test")
          .withTable("sensor")
          .build();

  @Test
  public void testStringAndTimestampKeys() {
    DataType rowType =
        DataTypes.ROW(
            DataTypes.FIELD("time", DataTypes.TIMESTAMP(3)),
            DataTypes.FIELD("device_id", DataTypes.STRING()),
            DataTypes.FIELD("temperature", DataTypes.DOUBLE()));
    IoTDBLookupReader reader = new IoTDBLookupReader(OPTIONS, rowType, new int[] {1, 0});

    GenericRowData keyRow =
        GenericRowData.of(
            StringData.fromString("d1"),
            TimestampData.fromLocalDateTime(LocalDateTime.of(2024, 1, 1, 12, 30)));

    assertEquals(
        "SELECT \"time\", \"device_id\", \"temperature\" FROM \"sensor\" "
            + "WHERE \"device_id\" = 'd1' AND \"time\" = CAST('2024-01-01T12:30' AS TIMESTAMP)",
        reader.buildLookupSql(keyRow));
  }

  @Test
  public void testStringKeyIsEscaped() {
    DataType rowType = DataTypes.ROW(DataTypes.FIELD("device_id", DataTypes.STRING()));
    IoTDBLookupReader reader = new IoTDBLookupReader(OPTIONS, rowType, new int[] {0});

    GenericRowData keyRow = GenericRowData.of(StringData.fromString("O'Brien"));

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE \"device_id\" = 'O''Brien'",
        reader.buildLookupSql(keyRow));
  }

  @Test
  public void testDateKey() {
    DataType rowType = DataTypes.ROW(DataTypes.FIELD("day", DataTypes.DATE()));
    IoTDBLookupReader reader = new IoTDBLookupReader(OPTIONS, rowType, new int[] {0});
    int epochDay = 19000;

    assertEquals(
        "SELECT \"day\" FROM \"sensor\" "
            + "WHERE \"day\" = CAST('"
            + LocalDate.ofEpochDay(epochDay)
            + "' AS DATE)",
        reader.buildLookupSql(GenericRowData.of(epochDay)));
  }

  @Test
  public void testCompositeNumericKey() {
    DataType rowType =
        DataTypes.ROW(
            DataTypes.FIELD("code", DataTypes.INT()), DataTypes.FIELD("value", DataTypes.BIGINT()));
    IoTDBLookupReader reader = new IoTDBLookupReader(OPTIONS, rowType, new int[] {0, 1});

    assertEquals(
        "SELECT \"code\", \"value\" FROM \"sensor\" WHERE \"code\" = 1 AND \"value\" = 2",
        reader.buildLookupSql(GenericRowData.of(1, 2L)));
  }

  @Test
  public void testNullKeyProducesNoQuery() {
    DataType rowType =
        DataTypes.ROW(
            DataTypes.FIELD("time", DataTypes.TIMESTAMP(3)),
            DataTypes.FIELD("device_id", DataTypes.STRING()));
    IoTDBLookupReader reader = new IoTDBLookupReader(OPTIONS, rowType, new int[] {1, 0});

    GenericRowData keyRow =
        GenericRowData.of(
            null, TimestampData.fromLocalDateTime(LocalDateTime.of(2024, 1, 1, 12, 30)));

    assertNull(reader.buildLookupSql(keyRow));
  }
}
