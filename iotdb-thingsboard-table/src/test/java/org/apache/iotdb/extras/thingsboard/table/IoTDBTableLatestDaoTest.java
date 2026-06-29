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
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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

  private static final String DERIVED_SQL_PREFIX =
      "SELECT time, bool_v, long_v, double_v, str_v, json_v FROM telemetry "
          + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
          + "AND entity_type='DEVICE' "
          + "AND entity_id='22222222-2222-2222-2222-222222222222' ";
  private static final String OVERLAY_SQL_PREFIX =
      "SELECT time, bool_v, long_v, double_v, str_v, json_v FROM telemetry_latest "
          + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
          + "AND entity_type='DEVICE' "
          + "AND entity_id='22222222-2222-2222-2222-222222222222' ";

  private final List<IoTDBTableLatestDao> daos = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (IoTDBTableLatestDao dao : daos) {
      dao.destroy();
    }
    daos.clear();
  }

  // ---- read merge: derived primary + overlay, max-ts-per-key, overlay wins on tie ----

  @Test
  void findLatestOpt_mergesDerivedAndOverlay_overlayNewerWins() throws Exception {
    TestContext context = newContext();
    // derived@100 (long 7), overlay@150 (long 99) -> overlay is newer and wins.
    stubReads(context.session(), rows(row(100L, "long_v", 7L)), rows(row(150L, "long_v", 99L)));

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "temperature").get(3, TimeUnit.SECONDS);

    assertTrue(result.isPresent());
    assertMappedEntry(result.get(), 150L, "temperature", DataType.LONG, 99L);

    List<String> queries = captureQueries(context.session(), 2);
    assertTrue(
        queries.contains(DERIVED_SQL_PREFIX + "AND key='temperature' ORDER BY time DESC LIMIT 1"),
        "derived point read SQL: " + queries);
    assertTrue(
        queries.contains(OVERLAY_SQL_PREFIX + "AND key='temperature' ORDER BY time DESC LIMIT 1"),
        "overlay point read SQL: " + queries);
  }

  @Test
  void findLatestOpt_derivedNewerThanOverlay_derivedWins() throws Exception {
    TestContext context = newContext();
    // derived@200 (double 3.5) beats overlay@150 (long 7).
    stubReads(context.session(), rows(row(200L, "double_v", 3.5D)), rows(row(150L, "long_v", 7L)));

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "pressure").get(3, TimeUnit.SECONDS);

    assertTrue(result.isPresent());
    assertMappedEntry(result.get(), 200L, "pressure", DataType.DOUBLE, 3.5D);
  }

  @Test
  void findLatestOpt_equalTimestamp_overlayWinsTie() throws Exception {
    TestContext context = newContext();
    // Same ts: the overlay value wins the tie (continues the B1 same-timestamp limitation).
    stubReads(context.session(), rows(row(150L, "long_v", 1L)), rows(row(150L, "str_v", "ov")));

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "k").get(3, TimeUnit.SECONDS);

    assertTrue(result.isPresent());
    assertMappedEntry(result.get(), 150L, "k", DataType.STRING, "ov");
  }

  @Test
  void findLatestOpt_overlayOnlyKey_returnsOverlay() throws Exception {
    TestContext context = newContext();
    // No derived row (the load-bearing latest-only / EntityView case): the overlay supplies it.
    stubReads(context.session(), rows(), rows(row(150L, "long_v", 5L)));

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "latest-only").get(3, TimeUnit.SECONDS);

    assertTrue(result.isPresent());
    assertMappedEntry(result.get(), 150L, "latest-only", DataType.LONG, 5L);
  }

  @Test
  void findLatestOpt_returnsEmptyWhenNeitherStoreHasRow() throws Exception {
    TestContext context = newContext();
    stubReads(context.session(), rows(), rows());

    Optional<TsKvEntry> result =
        context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "absent").get(3, TimeUnit.SECONDS);

    assertTrue(result.isEmpty());
  }

  @Test
  void findLatestOpt_failsFutureWhenDerivedRowHasTwoTypedColumns() throws Exception {
    TestContext context = newContext();
    stubReads(
        context.session(), rows(rowOf(3000L, Map.of("long_v", 7L, "str_v", "seven"))), rows());

    Throwable cause =
        assertFutureFailsWith(
            context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "same-ts"),
            IllegalStateException.class);
    assertTrue(cause.getMessage().contains("2 typed value columns set"));
  }

  @Test
  void findLatest_returnsNullStringEntryFallbackWhenEmpty() throws Exception {
    TestContext context = newContext();
    stubReads(context.session(), rows(), rows());

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
    stubReads(context.session(), rows(row(200L, "double_v", 3.5D)), rows());

    TsKvEntry entry =
        context.dao().findLatest(TENANT_ID, ENTITY_ID, "pressure").get(3, TimeUnit.SECONDS);

    assertMappedEntry(entry, 200L, "pressure", DataType.DOUBLE, 3.5D);
  }

  @Test
  void findAllLatest_mergesDerivedAndOverlayByMaxTsPerKey() throws Exception {
    TestContext context = newContext();
    // derived: bool@110, count@120, label@130. overlay: count@125 (newer -> wins), extra@140
    // (only).
    stubReads(
        context.session(),
        rows(
            aggRow("bool", 110L, "bool_v", true),
            aggRow("count", 120L, "long_v", 9L),
            aggRow("label", 130L, "str_v", "ok")),
        rows(
            overlayAllRow("count", 125L, "long_v", 99L),
            overlayAllRow("extra", 140L, "str_v", "x")));

    List<TsKvEntry> entries =
        context.dao().findAllLatest(TENANT_ID, ENTITY_ID).get(3, TimeUnit.SECONDS);
    entries.sort(java.util.Comparator.comparing(TsKvEntry::getKey));

    assertEquals(4, entries.size());
    assertMappedEntry(entries.get(0), 110L, "bool", DataType.BOOLEAN, true);
    assertMappedEntry(entries.get(1), 125L, "count", DataType.LONG, 99L);
    assertMappedEntry(entries.get(2), 140L, "extra", DataType.STRING, "x");
    assertMappedEntry(entries.get(3), 130L, "label", DataType.STRING, "ok");

    List<String> queries = captureQueries(context.session(), 2);
    assertTrue(
        queries.contains(
            "SELECT key, LAST_BY(bool_v, time) AS bool_v, LAST_BY(long_v, time) AS long_v, "
                + "LAST_BY(double_v, time) AS double_v, LAST_BY(str_v, time) AS str_v, "
                + "LAST_BY(json_v, time) AS json_v, MAX(time) AS last_ts FROM telemetry "
                + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
                + "AND entity_type='DEVICE' "
                + "AND entity_id='22222222-2222-2222-2222-222222222222' "
                + "GROUP BY key"),
        "derived findAll SQL: " + queries);
    assertTrue(
        queries.contains(
            "SELECT key, time, bool_v, long_v, double_v, str_v, json_v FROM telemetry_latest "
                + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
                + "AND entity_type='DEVICE' "
                + "AND entity_id='22222222-2222-2222-2222-222222222222'"),
        "overlay findAll SQL: " + queries);
  }

  @Test
  void findAllLatest_failsFutureWhenAggregatedKeyHasTwoTypedColumns() throws Exception {
    TestContext context = newContext();
    stubReads(
        context.session(),
        rows(
            aggRowOf("clean", 100L, Map.of("long_v", 1L)),
            aggRowOf("dirty", 200L, Map.of("long_v", 2L, "str_v", "x"))),
        rows());

    Throwable cause =
        assertFutureFailsWith(
            context.dao().findAllLatest(TENANT_ID, ENTITY_ID), IllegalStateException.class);
    assertTrue(cause.getMessage().contains("2 typed value columns set"));
  }

  // ---- saveLatest: overlay delete-then-insert upsert ----

  @Test
  void saveLatest_upsertsOverlayWithDeleteThenInsertAndNullVersion() throws Exception {
    TestContext context = newContext();

    Long version =
        context
            .dao()
            .saveLatest(TENANT_ID, ENTITY_ID, entry(500L, "temperature", DataType.LONG, 5L))
            .get(3, TimeUnit.SECONDS);

    // IoTDB has no sequence; saveLatest returns a null version (Phase-1 contract).
    assertNull(version);

    ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(deleteSql.capture());
    assertEquals(
        "DELETE FROM telemetry_latest "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature'",
        deleteSql.getValue());

    ArgumentCaptor<Tablet> tablet = ArgumentCaptor.forClass(Tablet.class);
    verify(context.session(), timeout(3000)).insert(tablet.capture());
    Tablet inserted = tablet.getValue();
    assertEquals("telemetry_latest", inserted.getTableName());
    assertEquals(1, inserted.getRowSize());
    assertEquals(500L, inserted.getTimestamp(0));

    // delete-then-insert ordering.
    InOrder order = inOrder(context.session());
    order.verify(context.session()).executeNonQueryStatement(anyString());
    order.verify(context.session()).insert(any());
  }

  @Test
  void saveLatest_tabletColumnsFollowLatestDdlTagOrder() throws Exception {
    // TAG-order rot guard: the overlay Tablet must follow the telemetry_latest DDL tag order
    // (entity_type, tenant_id, key, entity_id), then the five typed FIELDs, with NO
    // ColumnCategory.TIME entry (the time column is written via addTimestamp).
    TestContext context = newContext();

    context
        .dao()
        .saveLatest(TENANT_ID, ENTITY_ID, entry(500L, "temperature", DataType.LONG, 5L))
        .get(3, TimeUnit.SECONDS);

    ArgumentCaptor<Tablet> tablet = ArgumentCaptor.forClass(Tablet.class);
    verify(context.session(), timeout(3000)).insert(tablet.capture());
    Tablet inserted = tablet.getValue();

    List<String> columnNames =
        inserted.getSchemas().stream().map(IMeasurementSchema::getMeasurementName).toList();
    assertEquals(
        List.of(
            "entity_type",
            "tenant_id",
            "key",
            "entity_id",
            "bool_v",
            "long_v",
            "double_v",
            "str_v",
            "json_v"),
        columnNames);
    List<ColumnCategory> categories = inserted.getColumnTypes();
    assertEquals(9, categories.size());
    for (int i = 0; i < 4; i++) {
      assertEquals(ColumnCategory.TAG, categories.get(i), "tag column " + i);
    }
    for (int i = 4; i < 9; i++) {
      assertEquals(ColumnCategory.FIELD, categories.get(i), "field column " + i);
    }
  }

  @Test
  void saveLatest_escapesSingleQuotesInKey() throws Exception {
    TestContext context = newContext();

    context
        .dao()
        .saveLatest(TENANT_ID, ENTITY_ID, entry(5L, "a'b", DataType.STRING, "v"))
        .get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(deleteSql.capture());
    assertTrue(deleteSql.getValue().contains("key='a''b'"));
  }

  @Test
  void saveLatest_rejectsBlankKeyBeforeAnyWork() {
    TestContext context = newContext();

    assertThrows(
        IllegalArgumentException.class,
        () -> context.dao().saveLatest(TENANT_ID, ENTITY_ID, entry(1L, "  ", DataType.LONG, 1L)));
  }

  // ---- removeLatest: overlay-aware ----

  @Test
  void removeLatest_inWindow_deletesOverlayRowAndReportsRemoved() throws Exception {
    TestContext context = newContext();
    // Merged latest at ts=150, window [100, 200) covers it; default deleteLatest=true, no rewrite.
    stubReads(context.session(), rows(row(150L, "long_v", 42L)), rows(row(150L, "long_v", 42L)));

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertEquals("temperature", result.getKey());
    assertTrue(result.isRemoved());
    assertNull(result.getVersion());
    assertNull(result.getData());

    ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(deleteSql.capture());
    assertEquals(
        "DELETE FROM telemetry_latest "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature'",
        deleteSql.getValue());
    // Plain delete (no rewrite): no overlay INSERT.
    verify(context.session(), never()).insert(any());
  }

  @Test
  void removeLatest_overlayOnlyValue_inWindow_deletesOverlay() throws Exception {
    TestContext context = newContext();
    // Latest-only value lives only in the overlay; an in-window remove must delete it.
    stubReads(context.session(), rows(), rows(row(150L, "long_v", 42L)));

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertTrue(result.isRemoved());
    verify(context.session(), timeout(3000)).executeNonQueryStatement(anyString());
    verify(context.session(), never()).insert(any());
  }

  @Test
  void removeLatest_outsideWindow_reportsNotRemovedAndNoMutation() throws Exception {
    TestContext context = newContext();
    // Merged latest at ts=250, window [100, 200) does NOT cover it -> removed=false, no mutation.
    stubReads(context.session(), rows(row(250L, "long_v", 42L)), rows(row(250L, "long_v", 42L)));

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertEquals("temperature", result.getKey());
    assertFalse(result.isRemoved());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
    verify(context.session(), never()).insert(any());
  }

  @Test
  void removeLatest_noLatest_reportsNotRemoved() throws Exception {
    TestContext context = newContext();
    stubReads(context.session(), rows(), rows());

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS);

    assertEquals("temperature", result.getKey());
    assertFalse(result.isRemoved());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
    verify(context.session(), never()).insert(any());
  }

  @Test
  void removeLatest_rewrite_resurrectsPriorHistoryIntoOverlay() throws Exception {
    TestContext context = newContext();
    // Merged latest at ts=150 in window; rewriteLatestIfDeleted=true; the next-older history value
    // (telemetry, time < 100) is long 10 at ts=50 -> it is written back into the overlay and
    // returned as data so onTimeSeriesDelete emits a WS UPDATE.
    stubReads(
        context.session(),
        rows(row(150L, "long_v", 42L)),
        rows(row(150L, "long_v", 42L)),
        rows(row(50L, "long_v", 10L)));

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(
                TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L, true))
            .get(3, TimeUnit.SECONDS);

    assertTrue(result.isRemoved());
    assertNull(result.getVersion());
    assertEquals("temperature", result.getKey());
    TsKvEntry data = result.getData();
    assertEquals(50L, data.getTs());
    assertEquals(DataType.LONG, data.getDataType());
    assertEquals(10L, data.getValue());

    // The history-before query must have been issued against telemetry with a time-before
    // predicate.
    List<String> queries = captureAllQueries(context.session());
    assertTrue(
        queries.contains(
            DERIVED_SQL_PREFIX + "AND key='temperature' AND time < 100 ORDER BY time DESC LIMIT 1"),
        "rewrite history-before SQL: " + queries);
    // The resurrected prior is written back into the overlay (delete-then-insert).
    ArgumentCaptor<Tablet> tablet = ArgumentCaptor.forClass(Tablet.class);
    verify(context.session(), timeout(3000)).insert(tablet.capture());
    assertEquals("telemetry_latest", tablet.getValue().getTableName());
    assertEquals(50L, tablet.getValue().getTimestamp(0));
  }

  @Test
  void removeLatest_rewriteButNoOlderHistory_deletesOverlayAndReportsRemoved() throws Exception {
    TestContext context = newContext();
    // In-window latest, rewrite requested, but there is no older history to resurrect -> plain
    // overlay delete, removed=true with no data.
    stubReads(
        context.session(), rows(row(150L, "long_v", 42L)), rows(row(150L, "long_v", 42L)), rows());

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(
                TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L, true))
            .get(3, TimeUnit.SECONDS);

    assertTrue(result.isRemoved());
    assertNull(result.getData());
    verify(context.session(), timeout(3000)).executeNonQueryStatement(anyString());
    verify(context.session(), never()).insert(any());
  }

  @Test
  void removeLatest_deleteLatestFalse_reportsNotRemovedAndNoMutation() throws Exception {
    TestContext context = newContext();
    // deleteLatest=false (defensive branch; TB only calls removeLatest with deleteLatest=true):
    // even an in-window latest must not be removed and the overlay is untouched.
    stubReads(context.session(), rows(row(150L, "long_v", 42L)), rows(row(150L, "long_v", 42L)));

    TsKvLatestRemovingResult result =
        context
            .dao()
            .removeLatest(
                TENANT_ID,
                ENTITY_ID,
                new BaseDeleteTsKvQuery("temperature", 100L, 200L, false, false))
            .get(3, TimeUnit.SECONDS);

    assertFalse(result.isRemoved());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
    verify(context.session(), never()).insert(any());
  }

  // ---- input validation / escaping ----

  @Test
  void findLatestOpt_escapesQuotesInKey() throws Exception {
    TestContext context = newContext();
    stubReads(context.session(), rows(), rows());

    context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "a'b").get(3, TimeUnit.SECONDS);

    List<String> queries = captureQueries(context.session(), 2);
    assertTrue(queries.stream().anyMatch(s -> s.contains("key='a''b'")), "queries: " + queries);
  }

  @Test
  void findLatestOpt_rejectsBlankKeyBeforeQuery() {
    TestContext context = newContext();

    assertThrows(
        IllegalArgumentException.class,
        () -> context.dao().findLatestOpt(TENANT_ID, ENTITY_ID, "  "));
    verifyNoSession(context);
  }

  // ---- helpers ----

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

  private void verifyNoSession(TestContext context) {
    try {
      verify(context.pool(), never()).getSession();
    } catch (IoTDBConnectionException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Dispatches each {@code executeQueryStatement} to a FRESH dataset built from the matching row
   * array, keyed on the SQL: {@code telemetry_latest} -> overlay, {@code telemetry ... AND time <}
   * -> history-before, otherwise -> derived telemetry. A fresh dataset per call gives each read an
   * independent iterator (the derived + overlay merge issues two reads).
   */
  private void stubReads(ITableSession session, MockRow[] derived, MockRow[] overlay) {
    stubReads(session, derived, overlay, rows());
  }

  private void stubReads(
      ITableSession session, MockRow[] derived, MockRow[] overlay, MockRow[] history) {
    try {
      when(session.executeQueryStatement(anyString()))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("telemetry_latest")) {
                  return dataSet(overlay);
                }
                if (sql.contains(" AND time <")) {
                  return dataSet(history);
                }
                return dataSet(derived);
              });
    } catch (IoTDBConnectionException | StatementExecutionException e) {
      throw new AssertionError(e);
    }
  }

  private List<String> captureQueries(ITableSession session, int expected) throws Exception {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session, timeout(3000).times(expected)).executeQueryStatement(sql.capture());
    return sql.getAllValues();
  }

  private List<String> captureAllQueries(ITableSession session) throws Exception {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session, timeout(3000).atLeastOnce()).executeQueryStatement(sql.capture());
    return sql.getAllValues();
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

  private MockRow[] rows(MockRow... rows) {
    return rows;
  }

  /** A single typed-value latest row exposing {@code time} plus the one set typed column. */
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

  /**
   * A derived findAllLatest aggregated row exposing {@code key}, {@code last_ts}, one typed col.
   */
  private MockRow aggRow(String key, long lastTs, String column, Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("key", key);
    columns.put("last_ts", lastTs);
    columns.put(column, value);
    return new MockRow(columns);
  }

  /** A derived findAllLatest aggregated row with explicit typed columns (B1 multi-column cases). */
  private MockRow aggRowOf(String key, long lastTs, Map<String, Object> typed) {
    Map<String, Object> columns = new HashMap<>(typed);
    columns.put("key", key);
    columns.put("last_ts", lastTs);
    return new MockRow(columns);
  }

  /** An overlay findAllLatest row exposing {@code key}, {@code time} and one typed column. */
  private MockRow overlayAllRow(String key, long ts, String column, Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("key", key);
    columns.put("time", ts);
    columns.put(column, value);
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
