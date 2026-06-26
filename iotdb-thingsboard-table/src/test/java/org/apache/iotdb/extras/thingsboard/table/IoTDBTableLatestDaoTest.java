/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.extras.thingsboard.table;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;

import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseDeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IoTDBTableLatestDaoTest {
  private static final TenantId TENANT_ID =
      new TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final EntityId ENTITY_ID =
      new TestEntityId(UUID.fromString("22222222-2222-2222-2222-222222222222"), EntityType.DEVICE);

  private final List<IoTDBTableLatestDao> daos = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (IoTDBTableLatestDao dao : daos) {
      dao.destroy();
    }
    daos.clear();
  }

  @Test
  void findLatestOpt_buildsDescLimitOneSqlAndMapsRow() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(row(150L, "long_v", 42L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "temperature").get(3, TimeUnit.SECONDS);

    assertTrue(result.isPresent());
    assertMappedEntry(result.get(), 150L, "temperature", DataType.LONG, 42L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT time, bool_v, long_v, double_v, str_v, json_v FROM telemetry "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature' "
            + "ORDER BY time DESC LIMIT 1",
        sql.getValue());
  }

  @Test
  void findLatestOpt_returnsEmptyWhenNoRow() throws Exception {
    TestContext context = newContext();
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "absent").get(3, TimeUnit.SECONDS);

    assertTrue(result.isEmpty());
  }

  @Test
  void findLatestOpt_failsFutureWhenLatestRowHasTwoTypedColumns() throws Exception {
    TestContext context = newContext();
    SessionDataSet twoTypedDataSet = dataSet(rowOf(3000L, Map.of("long_v", 7L, "str_v", "seven")));
    when(context.session().executeQueryStatement(anyString())).thenReturn(twoTypedDataSet);

    Throwable cause =
        assertFutureFailsWith(
            context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "same-ts"),
            IllegalStateException.class);
    assertTrue(cause.getMessage().contains("2 typed value columns set"));
  }

  @Test
  void findLatest_returnsNullStringEntryFallbackWhenEmpty() throws Exception {
    TestContext context = newContext();
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    TsKvEntry fallback =
        context.dao().findLatest(TENANT_ID, ENTITY_ID, "missing").get(3, TimeUnit.SECONDS);

    assertInstanceOf(BasicTsKvEntry.class, fallback);
    assertEquals("missing", fallback.getKey());
    assertEquals(DataType.STRING, fallback.getDataType());
    assertNull(fallback.getValue());
    assertTrue(fallback.getStrValue().isEmpty());
  }

  @Test
  void findLatest_returnsPresentEntryWithoutFallback() throws Exception {
    TestContext context = newContext();
    SessionDataSet doubleDataSet = dataSet(row(200L, "double_v", 3.5D));
    when(context.session().executeQueryStatement(anyString())).thenReturn(doubleDataSet);

    TsKvEntry entry =
        context.dao().findLatest(TENANT_ID, ENTITY_ID, "pressure").get(3, TimeUnit.SECONDS);

    assertMappedEntry(entry, 200L, "pressure", DataType.DOUBLE, 3.5D);
  }

  @Test
  void findAllLatest_buildsLastByGroupBySqlAndMapsOneEntryPerKey() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet =
        dataSet(
            aggRow("bool", 110L, "bool_v", true),
            aggRow("count", 120L, "long_v", 9L),
            aggRow("label", 130L, "str_v", "ok"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<TsKvEntry> entries =
        context.dao().findAllLatest(TENANT_ID, ENTITY_ID).get(3, TimeUnit.SECONDS);

    assertEquals(3, entries.size());
    assertMappedEntry(entries.get(0), 110L, "bool", DataType.BOOLEAN, true);
    assertMappedEntry(entries.get(1), 120L, "count", DataType.LONG, 9L);
    assertMappedEntry(entries.get(2), 130L, "label", DataType.STRING, "ok");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT key, LAST_BY(bool_v, time) AS bool_v, LAST_BY(long_v, time) AS long_v, "
            + "LAST_BY(double_v, time) AS double_v, LAST_BY(str_v, time) AS str_v, "
            + "LAST_BY(json_v, time) AS json_v, MAX(time) AS last_ts FROM telemetry "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "GROUP BY key",
        sql.getValue());
  }

  @Test
  void findAllLatest_failsFutureWhenAggregatedKeyHasTwoTypedColumns() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet =
        dataSet(
            aggRowOf("clean", 100L, Map.of("long_v", 1L)),
            aggRowOf("dirty", 200L, Map.of("long_v", 2L, "str_v", "x")));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    Throwable cause =
        assertFutureFailsWith(
            context.dao().findAllLatest(TENANT_ID, ENTITY_ID), IllegalStateException.class);
    assertTrue(cause.getMessage().contains("2 typed value columns set"));
  }

  @Test
  void saveLatest_returnsImmediateNullVersionAndDoesNotTouchSession() throws Exception {
    TestContext context = newContext();

    ListenableFuture<Long> future =
        context
            .dao()
            .saveLatest(TENANT_ID, ENTITY_ID, entry(500L, "temperature", DataType.LONG, 5L));

    assertNull(future.get(3, TimeUnit.SECONDS));
    verify(context.pool(), never()).getSession();
  }

  @Test
  void removeLatest_marksRemovedWhenLatestInsideWindowWithoutExtraDelete() throws Exception {
    TestContext context = newContext();
    // Current latest at ts=150, delete window [100, 200) covers it -> removed=true.
    SessionDataSet dataSet = dataSet(row(150L, "long_v", 42L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertEquals("temperature", result.getKey());
    assertTrue(result.isRemoved());
    assertNull(result.getVersion());
    assertNull(result.getData());
    // No independent storage mutation: derived latest follows the Wk3 historical delete.
    verify(context.session(), never()).executeNonQueryStatement(anyString());
  }

  @Test
  void removeLatest_marksNotRemovedWhenLatestOutsideWindow() throws Exception {
    TestContext context = newContext();
    // Current latest at ts=250, delete window [100, 200) does NOT cover it -> removed=false,
    // so TB is not told to delete a latest value that is still valid.
    SessionDataSet dataSet = dataSet(row(250L, "long_v", 42L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertEquals("temperature", result.getKey());
    assertFalse(result.isRemoved());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
  }

  @Test
  void removeLatest_marksNotRemovedWhenNoLatestExists() throws Exception {
    TestContext context = newContext();
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertEquals("temperature", result.getKey());
    assertFalse(result.isRemoved());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
  }

  @Test
  void findLatestOpt_escapesQuotesInKey() throws Exception {
    TestContext context = newContext();
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "a'b").get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertTrue(sql.getValue().contains("key='a''b'"));
  }

  @Test
  void findLatestOpt_rejectsBlankKeyBeforeQuery() throws Exception {
    TestContext context = newContext();

    assertThrows(
        IllegalArgumentException.class,
        () -> context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "  "));
    verify(context.pool(), never()).getSession();
  }

  private TestContext newContext() {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    try {
      when(pool.getSession()).thenReturn(session);
    } catch (IoTDBConnectionException e) {
      throw new AssertionError(e);
    }
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getRead().setThreads(1);
    IoTDBTableLatestDao dao = new IoTDBTableLatestDao(pool, config);
    daos.add(dao);
    return new TestContext(dao, pool, session);
  }

  private void assertMappedEntry(
      TsKvEntry entry, long ts, String key, DataType dataType, Object value) {
    assertInstanceOf(BasicTsKvEntry.class, entry);
    assertEquals(ts, entry.getTs());
    assertEquals(key, entry.getKey());
    assertEquals(dataType, entry.getDataType());
    assertEquals(value, entry.getValue());
  }

  private Throwable assertFutureFailsWith(
      ListenableFuture<?> future, Class<? extends Throwable> expectedCause) throws Exception {
    ExecutionException exception =
        assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
    assertInstanceOf(expectedCause, exception.getCause());
    return exception.getCause();
  }

  private SessionDataSet dataSet(MockRow... rows)
      throws IoTDBConnectionException, StatementExecutionException {
    SessionDataSet dataSet = mock(SessionDataSet.class);
    SessionDataSet.DataIterator iterator = mock(SessionDataSet.DataIterator.class);
    AtomicInteger index = new AtomicInteger(-1);
    when(dataSet.iterator()).thenReturn(iterator);
    when(iterator.next()).thenAnswer(invocation -> index.incrementAndGet() < rows.length);
    when(iterator.isNull(anyString()))
        .thenAnswer(invocation -> rows[index.get()].isNull(invocation.getArgument(0)));
    when(iterator.getBoolean(anyString()))
        .thenAnswer(invocation -> rows[index.get()].get(invocation.getArgument(0)));
    when(iterator.getLong(anyString()))
        .thenAnswer(invocation -> rows[index.get()].get(invocation.getArgument(0)));
    when(iterator.getDouble(anyString()))
        .thenAnswer(invocation -> rows[index.get()].get(invocation.getArgument(0)));
    when(iterator.getString(anyString()))
        .thenAnswer(invocation -> String.valueOf(rows[index.get()].get(invocation.getArgument(0))));
    when(iterator.getTimestamp(anyString()))
        .thenAnswer(
            invocation -> new Timestamp((Long) rows[index.get()].get(invocation.getArgument(0))));
    return dataSet;
  }

  /** A single typed-value row exposing {@code time} plus the one set typed column. */
  private MockRow row(long ts, String column, Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("time", ts);
    columns.put(column, value);
    return new MockRow(columns);
  }

  /** A single typed-value row with explicit typed columns (for B1 multi-column cases). */
  private MockRow rowOf(long ts, Map<String, Object> typed) {
    Map<String, Object> columns = new HashMap<>(typed);
    columns.put("time", ts);
    return new MockRow(columns);
  }

  /** A findAllLatest aggregated row exposing {@code key}, {@code last_ts} and one typed column. */
  private MockRow aggRow(String key, long lastTs, String column, Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("key", key);
    columns.put("last_ts", lastTs);
    columns.put(column, value);
    return new MockRow(columns);
  }

  /** A findAllLatest aggregated row with explicit typed columns (for B1 multi-column cases). */
  private MockRow aggRowOf(String key, long lastTs, Map<String, Object> typed) {
    Map<String, Object> columns = new HashMap<>(typed);
    columns.put("key", key);
    columns.put("last_ts", lastTs);
    return new MockRow(columns);
  }

  private TestTsKvEntry entry(long ts, String key, DataType dataType, Object value) {
    return new TestTsKvEntry(ts, key, dataType, value);
  }

  private static final class MockRow {
    private final Map<String, Object> columns;

    private MockRow(Map<String, Object> columns) {
      this.columns = columns;
    }

    private boolean isNull(String column) {
      return columns.get(column) == null;
    }

    private Object get(String column) {
      return columns.get(column);
    }
  }

  private record TestContext(
      IoTDBTableLatestDao dao, ITableSessionPool pool, ITableSession session) {}

  private record TestEntityId(UUID id, EntityType entityType) implements EntityId {
    @Override
    public UUID getId() {
      return id;
    }

    @Override
    public EntityType getEntityType() {
      return entityType;
    }
  }

  private record TestTsKvEntry(long ts, String key, DataType dataType, Object value)
      implements TsKvEntry {
    @Override
    public long getTs() {
      return ts;
    }

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public DataType getDataType() {
      return dataType;
    }

    @Override
    public Optional<Boolean> getBooleanValue() {
      return dataType == DataType.BOOLEAN ? Optional.of((Boolean) value) : Optional.empty();
    }

    @Override
    public Optional<Long> getLongValue() {
      return dataType == DataType.LONG ? Optional.of((Long) value) : Optional.empty();
    }

    @Override
    public Optional<Double> getDoubleValue() {
      return dataType == DataType.DOUBLE ? Optional.of((Double) value) : Optional.empty();
    }

    @Override
    public Optional<String> getStrValue() {
      return dataType == DataType.STRING ? Optional.of((String) value) : Optional.empty();
    }

    @Override
    public Optional<String> getJsonValue() {
      return dataType == DataType.JSON ? Optional.of((String) value) : Optional.empty();
    }

    @Override
    public String getValueAsString() {
      return String.valueOf(value);
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public Long getVersion() {
      return null;
    }

    @Override
    public int getDataPoints() {
      return 1;
    }
  }
}
