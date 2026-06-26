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
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.write.record.Tablet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.util.TbPair;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IoTDBTableAttributesDaoTest {
  private static final TenantId TENANT_ID =
      new TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final EntityId ENTITY_ID =
      new TestEntityId(UUID.fromString("22222222-2222-2222-2222-222222222222"), EntityType.DEVICE);
  private static final EntityId SECOND_ENTITY_ID =
      new TestEntityId(UUID.fromString("33333333-3333-3333-3333-333333333333"), EntityType.ASSET);

  private final List<IoTDBTableAttributesDao> daos = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (IoTDBTableAttributesDao dao : daos) {
      dao.destroy();
    }
    daos.clear();
  }

  @Test
  void save_buildsDeleteThenInsertWithLastUpdateTsTimeAndOneTypedField() throws Exception {
    TestContext context = newContext();

    Long version =
        context
            .dao()
            .save(
                TENANT_ID,
                ENTITY_ID,
                AttributeScope.SERVER_SCOPE,
                attribute(7000L, "temperature", new LongDataEntry("temperature", 42L)))
            .get(3, TimeUnit.SECONDS);

    // IoTDB has no sequence; the save returns a null version (Phase-1 contract).
    assertNull(version);

    // delete-then-insert: the DELETE is a tag-only equality match (no time predicate) so it removes
    // the identity across all time, then a single attribute row is inserted at time=lastUpdateTs.
    ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(deleteSql.capture());
    assertEquals(
        "DELETE FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND attribute_scope='SERVER_SCOPE' "
            + "AND key='temperature'",
        deleteSql.getValue());

    ArgumentCaptor<Tablet> tablet = ArgumentCaptor.forClass(Tablet.class);
    verify(context.session(), timeout(3000)).insert(tablet.capture());
    Tablet inserted = tablet.getValue();
    assertEquals("entity_attributes", inserted.getTableName());
    assertEquals(1, inserted.getRowSize());
    assertEquals(7000L, inserted.getTimestamp(0));
  }

  @Test
  void save_tabletColumnsFollowNewDdlTagOrder() throws Exception {
    // TAG-order rot guard (Wk5 risk): the Tablet column schema must follow the entity_attributes
    // DDL tag order (attribute_scope, entity_type, tenant_id, key, entity_id), then the five typed
    // FIELDs, with NO ColumnCategory.TIME entry (the time column is written via addTimestamp).
    TestContext context = newContext();

    context
        .dao()
        .save(
            TENANT_ID,
            ENTITY_ID,
            AttributeScope.SERVER_SCOPE,
            attribute(7000L, "temperature", new LongDataEntry("temperature", 42L)))
        .get(3, TimeUnit.SECONDS);

    ArgumentCaptor<Tablet> tablet = ArgumentCaptor.forClass(Tablet.class);
    verify(context.session(), timeout(3000)).insert(tablet.capture());
    Tablet inserted = tablet.getValue();

    List<String> columnNames =
        inserted.getSchemas().stream()
            .map(org.apache.tsfile.write.schema.IMeasurementSchema::getMeasurementName)
            .toList();
    assertEquals(
        List.of(
            "attribute_scope",
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
    // The tablet schema carries exactly the 10 non-time columns: 5 TAG then 5 FIELD, with no other
    // category. The `time TIMESTAMP TIME` column is written via addTimestamp, not as a tablet
    // column, so it never appears here (tsfile's ColumnCategory has only TAG/FIELD/ATTRIBUTE).
    List<ColumnCategory> categories = inserted.getColumnTypes();
    assertEquals(10, categories.size());
    for (int i = 0; i < 5; i++) {
      assertEquals(ColumnCategory.TAG, categories.get(i), "tag column " + i);
    }
    for (int i = 5; i < 10; i++) {
      assertEquals(ColumnCategory.FIELD, categories.get(i), "field column " + i);
    }
  }

  @Test
  void save_runsDeleteBeforeInsert() throws Exception {
    TestContext context = newContext();

    context
        .dao()
        .save(
            TENANT_ID,
            ENTITY_ID,
            AttributeScope.CLIENT_SCOPE,
            attribute(1L, "k", new BooleanDataEntry("k", true)))
        .get(3, TimeUnit.SECONDS);

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(context.session());
    inOrder.verify(context.session()).executeNonQueryStatement(anyString());
    inOrder.verify(context.session()).insert(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void find_buildsSingleRowSqlAndMapsToBaseAttributeKvEntry() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(row(150L, "long_v", 42L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    Optional<AttributeKvEntry> result =
        context.dao().find(TENANT_ID, ENTITY_ID, AttributeScope.SERVER_SCOPE, "temperature");

    assertTrue(result.isPresent());
    AttributeKvEntry entry = result.get();
    assertEquals("temperature", entry.getKey());
    assertEquals(DataType.LONG, entry.getDataType());
    assertEquals(42L, entry.getValue());
    // The time column is the attribute's last-update timestamp.
    assertEquals(150L, entry.getLastUpdateTs());
    assertNull(entry.getVersion());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT time, bool_v, long_v, double_v, str_v, json_v FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND attribute_scope='SERVER_SCOPE' "
            + "AND key='temperature'",
        sql.getValue());
  }

  @Test
  void find_returnsEmptyWhenNoRow() throws Exception {
    TestContext context = newContext();
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    Optional<AttributeKvEntry> result =
        context.dao().find(TENANT_ID, ENTITY_ID, AttributeScope.SERVER_SCOPE, "absent");

    assertTrue(result.isEmpty());
  }

  @Test
  void find_mapsAllFiveTypes() throws Exception {
    assertSingleType("bool_v", true, DataType.BOOLEAN, true);
    assertSingleType("long_v", 7L, DataType.LONG, 7L);
    assertSingleType("double_v", 3.5D, DataType.DOUBLE, 3.5D);
    assertSingleType("str_v", "hi", DataType.STRING, "hi");
    assertSingleType("json_v", "{\"a\":1}", DataType.JSON, "{\"a\":1}");
  }

  private void assertSingleType(String column, Object stored, DataType type, Object expected)
      throws Exception {
    TestContext context = newContext();
    SessionDataSet typedDataSet = dataSet(row(99L, column, stored));
    when(context.session().executeQueryStatement(anyString())).thenReturn(typedDataSet);

    AttributeKvEntry entry =
        context.dao().find(TENANT_ID, ENTITY_ID, AttributeScope.SHARED_SCOPE, "k").orElseThrow();

    assertEquals(type, entry.getDataType());
    assertEquals(expected, entry.getValue());
    assertEquals(99L, entry.getLastUpdateTs());
  }

  @Test
  void findKeys_buildsInClauseAndMapsRowsPerKey() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet =
        dataSet(
            keyedRow("temperature", 100L, "long_v", 1L),
            keyedRow("humidity", 200L, "double_v", 2.0D));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<AttributeKvEntry> result =
        context
            .dao()
            .find(
                TENANT_ID,
                ENTITY_ID,
                AttributeScope.SERVER_SCOPE,
                List.of("temperature", "humidity"));

    assertEquals(2, result.size());
    assertEquals("temperature", result.get(0).getKey());
    assertEquals(1L, result.get(0).getValue());
    assertEquals(100L, result.get(0).getLastUpdateTs());
    assertEquals("humidity", result.get(1).getKey());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT key, time, bool_v, long_v, double_v, str_v, json_v FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND attribute_scope='SERVER_SCOPE' "
            + "AND key IN ('temperature','humidity')",
        sql.getValue());
  }

  @Test
  void findKeys_emptyCollectionReturnsEmptyAndSkipsQuery() throws Exception {
    TestContext context = newContext();

    assertEquals(
        List.of(),
        context.dao().find(TENANT_ID, ENTITY_ID, AttributeScope.SERVER_SCOPE, List.of()));
    verify(context.pool(), never()).getSession();
  }

  @Test
  void findAll_buildsScopeSqlAndMapsRowsPerKey() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet =
        dataSet(keyedRow("a", 10L, "long_v", 1L), keyedRow("b", 20L, "str_v", "x"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<AttributeKvEntry> result =
        context.dao().findAll(TENANT_ID, ENTITY_ID, AttributeScope.CLIENT_SCOPE);

    assertEquals(2, result.size());
    assertEquals("a", result.get(0).getKey());
    assertEquals("b", result.get(1).getKey());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT key, time, bool_v, long_v, double_v, str_v, json_v FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND attribute_scope='CLIENT_SCOPE'",
        sql.getValue());
  }

  @Test
  void removeAll_returnsOneFuturePerKeyAndEachDeletes() throws Exception {
    TestContext context = newContext();

    List<ListenableFuture<String>> futures =
        context
            .dao()
            .removeAll(
                TENANT_ID, ENTITY_ID, AttributeScope.SERVER_SCOPE, List.of("temperature", "speed"));

    assertEquals(2, futures.size());
    assertEquals("temperature", futures.get(0).get(3, TimeUnit.SECONDS));
    assertEquals("speed", futures.get(1).get(3, TimeUnit.SECONDS));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(2)).executeNonQueryStatement(sql.capture());
    List<String> statements = sql.getAllValues();
    assertTrue(
        statements.stream()
            .anyMatch(
                s ->
                    s.equals(
                        "DELETE FROM entity_attributes "
                            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
                            + "AND entity_type='DEVICE' "
                            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
                            + "AND attribute_scope='SERVER_SCOPE' "
                            + "AND key='temperature'")));
    assertTrue(statements.stream().anyMatch(s -> s.endsWith("AND key='speed'")));
  }

  @Test
  void removeAllWithVersions_returnsTbPairKeyNullVersionPerKey() throws Exception {
    TestContext context = newContext();

    List<ListenableFuture<TbPair<String, Long>>> futures =
        context
            .dao()
            .removeAllWithVersions(
                TENANT_ID, ENTITY_ID, AttributeScope.SERVER_SCOPE, List.of("temperature"));

    assertEquals(1, futures.size());
    TbPair<String, Long> pair = futures.get(0).get(3, TimeUnit.SECONDS);
    assertEquals("temperature", pair.getFirst());
    assertNull(pair.getSecond());

    verify(context.session(), timeout(3000)).executeNonQueryStatement(anyString());
  }

  @Test
  void findNextBatch_throwsUnsupportedOperation() throws Exception {
    TestContext context = newContext();

    UnsupportedOperationException ex =
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.dao().findNextBatch(UUID.randomUUID(), 1, 1, 100));
    assertTrue(ex.getMessage().contains("migration helper"));
    verify(context.pool(), never()).getSession();
  }

  @Test
  void findAllKeysByEntityIds_buildsDistinctKeyByEntitySql() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(keyRow("temperature"), keyRow("humidity"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<String> keys =
        context.dao().findAllKeysByEntityIds(TENANT_ID, List.of(ENTITY_ID, SECOND_ENTITY_ID));

    assertEquals(List.of("temperature", "humidity"), keys);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT DISTINCT key FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND ((entity_type='DEVICE' AND entity_id='22222222-2222-2222-2222-222222222222') "
            + "OR (entity_type='ASSET' AND entity_id='33333333-3333-3333-3333-333333333333')) "
            + "ORDER BY key",
        sql.getValue());
  }

  @Test
  void findAllKeysByEntityIdsAndScope_addsScopePredicate() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(keyRow("temperature"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<String> keys =
        context
            .dao()
            .findAllKeysByEntityIdsAndScope(
                TENANT_ID, List.of(ENTITY_ID), AttributeScope.SHARED_SCOPE);

    assertEquals(List.of("temperature"), keys);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT DISTINCT key FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND attribute_scope='SHARED_SCOPE' "
            + "AND ((entity_type='DEVICE' AND entity_id='22222222-2222-2222-2222-222222222222')) "
            + "ORDER BY key",
        sql.getValue());
  }

  @Test
  void findAllKeysByEntityIdsAndScopeAsync_runsTheSameDistinctKeySql() throws Exception {
    // [v4.3.1.2] async wrapper: same SQL as the synchronous overload, on the IO executor.
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(keyRow("temperature"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<String> keys =
        context
            .dao()
            .findAllKeysByEntityIdsAndScopeAsync(
                TENANT_ID, List.of(ENTITY_ID), AttributeScope.SHARED_SCOPE)
            .get(3, TimeUnit.SECONDS);

    assertEquals(List.of("temperature"), keys);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT DISTINCT key FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND attribute_scope='SHARED_SCOPE' "
            + "AND ((entity_type='DEVICE' AND entity_id='22222222-2222-2222-2222-222222222222')) "
            + "ORDER BY key",
        sql.getValue());
  }

  @Test
  void findAllKeysByEntityIdsAndScopeAsync_emptyEntityIdsReturnsImmediateEmpty() throws Exception {
    TestContext context = newContext();

    List<String> keys =
        context
            .dao()
            .findAllKeysByEntityIdsAndScopeAsync(TENANT_ID, List.of(), AttributeScope.SHARED_SCOPE)
            .get(3, TimeUnit.SECONDS);

    assertEquals(List.of(), keys);
    verify(context.pool(), never()).getSession();
  }

  @Test
  void findLatestByEntityIdsAndScope_buildsBulkOrSetSqlAndMapsRows() throws Exception {
    // [v4.3.1.2] bulk latest read: one SELECT over the entity OR-set in a scope, one row per
    // (entity, key) because delete-then-insert keeps a single current row per identity.
    TestContext context = newContext();
    SessionDataSet dataSet =
        dataSet(keyedRow("a", 10L, "long_v", 1L), keyedRow("b", 20L, "str_v", "x"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<AttributeKvEntry> result =
        context
            .dao()
            .findLatestByEntityIdsAndScope(
                TENANT_ID, List.of(ENTITY_ID, SECOND_ENTITY_ID), AttributeScope.SERVER_SCOPE);

    assertEquals(2, result.size());
    assertEquals("a", result.get(0).getKey());
    assertEquals(1L, result.get(0).getValue());
    assertEquals(10L, result.get(0).getLastUpdateTs());
    assertNull(result.get(0).getVersion());
    assertEquals("b", result.get(1).getKey());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT key, time, bool_v, long_v, double_v, str_v, json_v FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND attribute_scope='SERVER_SCOPE' "
            + "AND ((entity_type='DEVICE' AND entity_id='22222222-2222-2222-2222-222222222222') "
            + "OR (entity_type='ASSET' AND entity_id='33333333-3333-3333-3333-333333333333'))",
        sql.getValue());
  }

  @Test
  void findLatestByEntityIdsAndScopeAsync_runsTheSameBulkRead() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(keyedRow("a", 10L, "long_v", 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<AttributeKvEntry> result =
        context
            .dao()
            .findLatestByEntityIdsAndScopeAsync(
                TENANT_ID, List.of(ENTITY_ID), AttributeScope.SERVER_SCOPE)
            .get(3, TimeUnit.SECONDS);

    assertEquals(1, result.size());
    assertEquals("a", result.get(0).getKey());
    assertEquals(1L, result.get(0).getValue());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT key, time, bool_v, long_v, double_v, str_v, json_v FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND attribute_scope='SERVER_SCOPE' "
            + "AND ((entity_type='DEVICE' AND entity_id='22222222-2222-2222-2222-222222222222'))",
        sql.getValue());
  }

  @Test
  void findLatestByEntityIdsAndScopeAsync_emptyEntityIdsReturnsImmediateEmpty() throws Exception {
    TestContext context = newContext();

    List<AttributeKvEntry> result =
        context
            .dao()
            .findLatestByEntityIdsAndScopeAsync(TENANT_ID, List.of(), AttributeScope.SERVER_SCOPE)
            .get(3, TimeUnit.SECONDS);

    assertEquals(List.of(), result);
    verify(context.pool(), never()).getSession();
  }

  @Test
  void findAllKeysByEntityIds_emptyListReturnsEmptyAndSkipsQuery() throws Exception {
    TestContext context = newContext();

    assertEquals(List.of(), context.dao().findAllKeysByEntityIds(TENANT_ID, List.of()));
    verify(context.pool(), never()).getSession();
  }

  @Test
  void findAllKeysByDeviceProfileId_nullProfileReturnsTenantWideDistinctKeys() throws Exception {
    TestContext context = newContext();
    SessionDataSet dataSet = dataSet(keyRow("temperature"), keyRow("humidity"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    List<String> keys = context.dao().findAllKeysByDeviceProfileId(TENANT_ID, null);

    assertEquals(List.of("temperature", "humidity"), keys);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT DISTINCT key FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "ORDER BY key",
        sql.getValue());
  }

  @Test
  void findAllKeysByDeviceProfileId_nonNullProfileReturnsEmptyDeferred() throws Exception {
    TestContext context = newContext();

    List<String> keys =
        context
            .dao()
            .findAllKeysByDeviceProfileId(
                TENANT_ID,
                new DeviceProfileId(UUID.fromString("44444444-4444-4444-4444-444444444444")));

    assertEquals(List.of(), keys);
    verify(context.pool(), never()).getSession();
  }

  @Test
  void removeAllByEntityId_selectsThenDeletesAndReturnsScopeKeyPairs() throws Exception {
    TestContext context = newContext();
    SessionDataSet selectResult =
        dataSet(scopeKeyRow("SERVER_SCOPE", "temperature"), scopeKeyRow("CLIENT_SCOPE", "active"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(selectResult);

    List<Pair<AttributeScope, String>> removed =
        context.dao().removeAllByEntityId(TENANT_ID, ENTITY_ID);

    assertEquals(2, removed.size());
    assertEquals(AttributeScope.SERVER_SCOPE, removed.get(0).getLeft());
    assertEquals("temperature", removed.get(0).getRight());
    assertEquals(AttributeScope.CLIENT_SCOPE, removed.get(1).getLeft());
    assertEquals("active", removed.get(1).getRight());

    ArgumentCaptor<String> selectSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(selectSql.capture());
    assertEquals(
        "SELECT DISTINCT attribute_scope, key FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "ORDER BY attribute_scope, key",
        selectSql.getValue());

    ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(deleteSql.capture());
    assertEquals(
        "DELETE FROM entity_attributes "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222'",
        deleteSql.getValue());
  }

  @Test
  void removeAllByEntityId_writeLockExcludesConcurrentSaveUntilItCompletes() throws Exception {
    // removeAllByEntityId holds the per-entity WRITE lock around its select+delete; a concurrent
    // same-entity save holds the per-entity READ lock, so the save cannot run its
    // delete-then-insert
    // until removeAllByEntityId releases the write lock. This deterministically proves the added
    // serialization (no row can be re-inserted between the entity-wide select and delete). It is
    // made deterministic by blocking the removeAll SELECT on a latch while the write lock is held,
    // then asserting the save's writes have not yet executed.
    TestContext context = newContext();
    CountDownLatch selectEntered = new CountDownLatch(1);
    CountDownLatch releaseSelect = new CountDownLatch(1);
    SessionDataSet selectResult = dataSet(scopeKeyRow("SERVER_SCOPE", "temperature"));
    // The first query is removeAllByEntityId's SELECT DISTINCT; block it (while the entity write
    // lock is held) until the test releases it.
    when(context.session().executeQueryStatement(anyString()))
        .thenAnswer(
            invocation -> {
              selectEntered.countDown();
              if (!releaseSelect.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("removeAll SELECT was not released in time");
              }
              return selectResult;
            });

    Thread remover =
        new Thread(() -> context.dao().removeAllByEntityId(TENANT_ID, ENTITY_ID), "remover");
    remover.start();
    // Wait until removeAllByEntityId is inside its SELECT, i.e. holding the entity write lock.
    assertTrue(selectEntered.await(5, TimeUnit.SECONDS), "removeAll did not reach its SELECT");

    // Start a concurrent same-entity save; it must block on the entity read lock and NOT issue any
    // DELETE/INSERT while the write lock is held.
    ListenableFuture<Long> saveFuture =
        context
            .dao()
            .save(
                TENANT_ID,
                ENTITY_ID,
                AttributeScope.SERVER_SCOPE,
                attribute(7L, "humidity", new LongDataEntry("humidity", 5L)));

    // Give the save's io-task a chance to run; it must remain blocked on the entity read lock.
    Thread.sleep(200L);
    verify(context.session(), never()).executeNonQueryStatement(anyString());
    assertFalse(saveFuture.isDone(), "save must not complete while removeAll holds the write lock");

    // Release removeAll; it finishes (its DELETE runs), then the save acquires the read lock and
    // runs its delete-then-insert.
    releaseSelect.countDown();
    saveFuture.get(5, TimeUnit.SECONDS);
    remover.join(5_000L);

    // Both the removeAll entity-wide DELETE and the save's identity DELETE must have executed; the
    // save's INSERT (tablet) too. Two executeNonQueryStatement calls = removeAll DELETE + save
    // DELETE (the save INSERT goes through session.insert, not executeNonQueryStatement).
    verify(context.session(), timeout(3000).times(2)).executeNonQueryStatement(anyString());
  }

  @Test
  void findTakesPerIdentityLockSoItCannotReadDuringTheDeleteInsertGap() throws Exception {
    // find(single) takes the same per-identity lock save() holds across its (non-atomic)
    // delete-then-insert, so a concurrent same-identity point-read cannot run its SELECT during the
    // save's critical section -- it therefore never observes the transient empty window between the
    // DELETE and the INSERT. Deterministic: block the save's DELETE on a latch while it holds the
    // identity lock, then assert a concurrent same-identity find() stays blocked (never issues its
    // SELECT) until the save releases the lock.
    TestContext context = newContext();
    CountDownLatch deleteEntered = new CountDownLatch(1);
    CountDownLatch releaseDelete = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              deleteEntered.countDown();
              if (!releaseDelete.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("save DELETE was not released in time");
              }
              return null;
            })
        .when(context.session())
        .executeNonQueryStatement(anyString());
    SessionDataSet valueRow = dataSet(row(7L, "long_v", 5L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(valueRow);

    ListenableFuture<Long> saveFuture =
        context
            .dao()
            .save(
                TENANT_ID,
                ENTITY_ID,
                AttributeScope.SERVER_SCOPE,
                attribute(7L, "humidity", new LongDataEntry("humidity", 5L)));
    assertTrue(deleteEntered.await(5, TimeUnit.SECONDS), "save did not reach its DELETE");

    CountDownLatch findDone = new CountDownLatch(1);
    boolean[] present = {false};
    Thread finder =
        new Thread(
            () -> {
              present[0] =
                  context
                      .dao()
                      .find(TENANT_ID, ENTITY_ID, AttributeScope.SERVER_SCOPE, "humidity")
                      .isPresent();
              findDone.countDown();
            },
            "finder");
    finder.start();

    // While the save holds the identity lock (its DELETE is blocked), find() must block on the same
    // identity lock and NOT run its SELECT.
    Thread.sleep(200L);
    assertEquals(
        1L, findDone.getCount(), "find() must block on the per-identity lock during the save");
    verify(context.session(), never()).executeQueryStatement(anyString());

    // Release the save; it completes and releases the identity lock; find() then runs and returns.
    releaseDelete.countDown();
    saveFuture.get(5, TimeUnit.SECONDS);
    assertTrue(
        findDone.await(5, TimeUnit.SECONDS),
        "find() should complete after the save releases the lock");
    finder.join(5_000L);
    assertTrue(present[0], "find() must return the value, never the empty delete->insert gap");
  }

  @Test
  void save_escapesSingleQuotesInScopeAndKey() throws Exception {
    TestContext context = newContext();

    context
        .dao()
        .save(
            TENANT_ID,
            ENTITY_ID,
            AttributeScope.SERVER_SCOPE,
            attribute(5L, "a'b", new StringDataEntry("a'b", "v")))
        .get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(deleteSql.capture());
    assertTrue(deleteSql.getValue().contains("key='a''b'"));
  }

  @Test
  void save_blankKeyFailsFast() {
    TestContext context = newContext();

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                context
                    .dao()
                    .save(
                        TENANT_ID,
                        ENTITY_ID,
                        AttributeScope.SERVER_SCOPE,
                        attribute(1L, "   ", new LongDataEntry("   ", 1L))));
    assertTrue(ex.getMessage().contains("must not be blank"));
  }

  @Test
  void constructor_failsWhenClusterModeUnset() {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getRead().setThreads(1);
    // iotdb.attributes.cluster_mode defaults to blank -> must fail fast.

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new IoTDBTableAttributesDao(pool, config));
    assertTrue(ex.getMessage().contains("cluster_mode"));
  }

  @Test
  void constructor_acceptsDisabledClusterMode() {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getRead().setThreads(1);
    config.getAttributes().setClusterMode("disabled");

    IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config);
    daos.add(dao);
  }

  // ---- helpers (mirrors IoTDBTableLatestDaoTest) ----

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
    config.getAttributes().setClusterMode("sticky-routing");
    IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config);
    daos.add(dao);
    return new TestContext(dao, pool, session);
  }

  private TestAttributeKvEntry attribute(long lastUpdateTs, String key, KvEntry value) {
    return new TestAttributeKvEntry(lastUpdateTs, value);
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

  /** A find()-single-key row exposing {@code time} plus one typed column. */
  private MockRow row(long ts, String column, Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("time", ts);
    columns.put(column, value);
    return new MockRow(columns);
  }

  /** A find(keys)/findAll row exposing {@code key}, {@code time} and one typed column. */
  private MockRow keyedRow(String key, long ts, String column, Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("key", key);
    columns.put("time", ts);
    columns.put(column, value);
    return new MockRow(columns);
  }

  /** A DISTINCT key row exposing only the {@code key} column. */
  private MockRow keyRow(String key) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("key", key);
    return new MockRow(columns);
  }

  /** A DISTINCT (scope, key) row for removeAllByEntityId. */
  private MockRow scopeKeyRow(String scope, String key) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("attribute_scope", scope);
    columns.put("key", key);
    return new MockRow(columns);
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
      IoTDBTableAttributesDao dao, ITableSessionPool pool, ITableSession session) {}

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

  private static final class TestAttributeKvEntry extends BaseAttributeKvEntry {
    private TestAttributeKvEntry(long lastUpdateTs, KvEntry kv) {
      super(kv, lastUpdateTs);
    }
  }
}
