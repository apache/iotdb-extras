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

package org.apache.iotdb.extras.thingsboard.table;

import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.StatementExecutionException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IoTDBTableBaseDaoTest {

  @Test
  void getEntry_mapsBooleanColumn() throws StatementExecutionException {
    SessionDataSet.DataIterator row = mock(SessionDataSet.DataIterator.class);
    when(row.isNull("bool_v")).thenReturn(false);
    when(row.isNull("long_v")).thenReturn(true);
    when(row.isNull("double_v")).thenReturn(true);
    when(row.isNull("str_v")).thenReturn(true);
    when(row.isNull("json_v")).thenReturn(true);
    when(row.getBoolean("bool_v")).thenReturn(true);

    TypedKvValue result = baseDao().getEntry(row);

    assertTrue(result.hasValue());
    assertTrue(result.booleanValue());
    assertNull(result.longValue());
    assertNull(result.doubleValue());
    assertNull(result.stringValue());
    assertNull(result.jsonValue());
  }

  @Test
  void getEntry_mapsLongColumn() throws StatementExecutionException {
    SessionDataSet.DataIterator row = mock(SessionDataSet.DataIterator.class);
    when(row.isNull("bool_v")).thenReturn(true);
    when(row.isNull("long_v")).thenReturn(false);
    when(row.isNull("double_v")).thenReturn(true);
    when(row.isNull("str_v")).thenReturn(true);
    when(row.isNull("json_v")).thenReturn(true);
    when(row.getLong("long_v")).thenReturn(42L);

    TypedKvValue result = baseDao().getEntry(row);

    assertTrue(result.hasValue());
    assertNull(result.booleanValue());
    assertEquals(42L, result.longValue());
    assertNull(result.doubleValue());
    assertNull(result.stringValue());
    assertNull(result.jsonValue());
  }

  @Test
  void getEntry_handlesAllNullRow() throws StatementExecutionException {
    SessionDataSet.DataIterator row = mock(SessionDataSet.DataIterator.class);
    when(row.isNull("bool_v")).thenReturn(true);
    when(row.isNull("long_v")).thenReturn(true);
    when(row.isNull("double_v")).thenReturn(true);
    when(row.isNull("str_v")).thenReturn(true);
    when(row.isNull("json_v")).thenReturn(true);

    TypedKvValue result = baseDao().getEntry(row);

    assertFalse(result.hasValue());
  }

  @Test
  void getEntry_throwsOnMultipleNonNull() throws StatementExecutionException {
    SessionDataSet.DataIterator row = mock(SessionDataSet.DataIterator.class);
    when(row.isNull("bool_v")).thenReturn(false);
    when(row.isNull("long_v")).thenReturn(false);
    when(row.isNull("double_v")).thenReturn(true);
    when(row.isNull("str_v")).thenReturn(true);
    when(row.isNull("json_v")).thenReturn(true);

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> baseDao().getEntry(row));

    assertTrue(exception.getMessage().contains("telemetry schema stores exactly one typed value"));
  }

  private IoTDBTableBaseDao baseDao() {
    return new IoTDBTableBaseDao(mock(ITableSessionPool.class));
  }
}
