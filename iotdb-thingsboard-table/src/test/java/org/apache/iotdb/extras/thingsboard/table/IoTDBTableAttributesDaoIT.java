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
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.util.TbPair;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class IoTDBTableAttributesDaoIT {
  // Cold testcontainer first writes/reads are slower than a warm production node, so the
  // per-future assertion timeout is generous.
  private static final int FUTURE_TIMEOUT_SECONDS = 30;
  private static final Duration IOTDB_STARTUP_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IOTDB_READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration IOTDB_READY_POLL_INTERVAL = Duration.ofMillis(500);

  @Container
  static final GenericContainer<?> IOTDB =
      new GenericContainer<>(DockerImageName.parse("apache/iotdb:2.0.8-standalone"))
          .withExposedPorts(6667)
          // IoTDB binds its client RPC service to dn_rpc_address (default 127.0.0.1); bind to all
          // interfaces so the Testcontainers port-mapped session handshake succeeds.
          .withEnv("dn_rpc_address", "0.0.0.0")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(IOTDB_STARTUP_TIMEOUT));

  @Test
  void saveThenFind_roundTripsAllFiveTypesWithLastUpdateTs() throws Exception {
    TestScope scope = scope("attr_types", "55555555-5555-5555-5555-555555555501");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1000L, "bool", bool("bool", true)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1001L, "long", lng("long", 42L)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1002L, "double", dbl("double", 4.2D)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1003L, "string", str("string", "v")));
        save(
            dao,
            scope,
            AttributeScope.SERVER_SCOPE,
            attr(1004L, "json", json("json", "{\"v\":1}")));

        assertFind(dao, scope, AttributeScope.SERVER_SCOPE, "bool", 1000L, DataType.BOOLEAN, true);
        assertFind(dao, scope, AttributeScope.SERVER_SCOPE, "long", 1001L, DataType.LONG, 42L);
        assertFind(dao, scope, AttributeScope.SERVER_SCOPE, "double", 1002L, DataType.DOUBLE, 4.2D);
        assertFind(dao, scope, AttributeScope.SERVER_SCOPE, "string", 1003L, DataType.STRING, "v");
        assertFind(
            dao, scope, AttributeScope.SERVER_SCOPE, "json", 1004L, DataType.JSON, "{\"v\":1}");
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void save_isDeleteThenInsert_keepsExactlyOneRowOnUpdate() throws Exception {
    TestScope scope = scope("attr_update", "55555555-5555-5555-5555-555555555502");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        // First write a LONG at ts=1000, then overwrite the same identity with a STRING at ts=2000.
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1000L, "temp", lng("temp", 1L)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(2000L, "temp", str("temp", "two")));

        // delete-then-insert convergence: exactly one row, one typed FIELD, the newer value.
        assertEquals(1, attributeRowCount(pool, scope, AttributeScope.SERVER_SCOPE, "temp"));
        AttributeKvEntry found =
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, "temp")
                .orElseThrow();
        assertEquals(DataType.STRING, found.getDataType());
        assertEquals("two", found.getValue());
        assertEquals(2000L, found.getLastUpdateTs());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void sameTimestampTypeChange_getEntryDoesNotThrow() throws Exception {
    // B1 regression: rewriting the same identity at the SAME timestamp with a different type must
    // still converge to one typed FIELD (delete-then-insert), so find()'s getEntry never sees two
    // typed columns and never throws the B1 fail-fast.
    TestScope scope = scope("attr_b1", "55555555-5555-5555-5555-555555555510");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(3000L, "temp", lng("temp", 1L)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(3000L, "temp", str("temp", "two")));

        assertEquals(1, attributeRowCount(pool, scope, AttributeScope.SERVER_SCOPE, "temp"));
        AttributeKvEntry found =
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, "temp")
                .orElseThrow();
        assertEquals(DataType.STRING, found.getDataType());
        assertEquals("two", found.getValue());
        assertEquals(3000L, found.getLastUpdateTs());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void scopesAreIsolated() throws Exception {
    TestScope scope = scope("attr_scope", "55555555-5555-5555-5555-555555555503");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.CLIENT_SCOPE, attr(10L, "shared", lng("shared", 1L)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(20L, "shared", lng("shared", 2L)));

        assertEquals(
            1L,
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.CLIENT_SCOPE, "shared")
                .orElseThrow()
                .getValue());
        assertEquals(
            2L,
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, "shared")
                .orElseThrow()
                .getValue());

        // Deleting the CLIENT scope key does not touch the SERVER scope key.
        removeAll(dao, scope, AttributeScope.CLIENT_SCOPE, List.of("shared"));
        assertTrue(
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.CLIENT_SCOPE, "shared")
                .isEmpty());
        assertTrue(
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, "shared")
                .isPresent());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void findAllAndFindKeys_returnAllAttributesInScope() throws Exception {
    TestScope scope = scope("attr_all", "55555555-5555-5555-5555-555555555504");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1L, "a", lng("a", 1L)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(2L, "b", str("b", "x")));

        List<AttributeKvEntry> all =
            dao.findAll(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE);
        assertEquals(2, all.size());
        assertEquals(Set.of("a", "b"), new HashSet<>(all.stream().map(KvEntry::getKey).toList()));

        List<AttributeKvEntry> some =
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, List.of("a"));
        assertEquals(1, some.size());
        assertEquals("a", some.get(0).getKey());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void save_returnsLastUpdateTs_removeAllVersionNull_findEmpty() throws Exception {
    TestScope scope = scope("attr_remove", "55555555-5555-5555-5555-555555555505");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1L, "temp", lng("temp", 1L)));

        Long version =
            dao.save(
                    scope.tenantId(),
                    scope.entityId(),
                    AttributeScope.SERVER_SCOPE,
                    attr(2L, "temp", lng("temp", 2L)))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // save returns a non-null version (the attribute's lastUpdateTs); ThingsBoard unboxes it.
        assertEquals(2L, version.longValue());

        List<ListenableFuture<TbPair<String, Long>>> futures =
            dao.removeAllWithVersions(
                scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, List.of("temp"));
        TbPair<String, Long> pair = futures.get(0).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("temp", pair.getFirst());
        assertNull(pair.getSecond());

        assertTrue(
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, "temp")
                .isEmpty());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void removeAllByEntityId_returnsScopeKeyPairsAndClearsEntity() throws Exception {
    TestScope scope = scope("attr_byent", "55555555-5555-5555-5555-555555555506");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1L, "a", lng("a", 1L)));
        save(dao, scope, AttributeScope.CLIENT_SCOPE, attr(2L, "b", str("b", "x")));

        List<Pair<AttributeScope, String>> removed =
            dao.removeAllByEntityId(scope.tenantId(), scope.entityId());
        Set<String> pairKeys = new HashSet<>();
        for (Pair<AttributeScope, String> pair : removed) {
          pairKeys.add(pair.getLeft().name() + ":" + pair.getRight());
        }
        assertEquals(Set.of("SERVER_SCOPE:a", "CLIENT_SCOPE:b"), pairKeys);

        assertTrue(
            dao.findAll(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE).isEmpty());
        assertTrue(
            dao.findAll(scope.tenantId(), scope.entityId(), AttributeScope.CLIENT_SCOPE).isEmpty());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void keyDiscovery_distinctKeysAndDeviceProfileNullTenantWide() throws Exception {
    TestScope scope = scope("attr_keys", "55555555-5555-5555-5555-555555555507");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1L, "a", lng("a", 1L)));
        save(dao, scope, AttributeScope.CLIENT_SCOPE, attr(2L, "b", str("b", "x")));

        assertEquals(
            Set.of("a", "b"),
            new HashSet<>(dao.findAllKeysByEntityIds(scope.tenantId(), List.of(scope.entityId()))));
        assertEquals(
            Set.of("a"),
            new HashSet<>(
                dao.findAllKeysByEntityIdsAndScope(
                    scope.tenantId(), List.of(scope.entityId()), AttributeScope.SERVER_SCOPE)));
        // [v4.3.1.2] async key discovery returns the same set as the synchronous overload.
        assertEquals(
            Set.of("a"),
            new HashSet<>(
                dao.findAllKeysByEntityIdsAndScopeAsync(
                        scope.tenantId(), List.of(scope.entityId()), AttributeScope.SERVER_SCOPE)
                    .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
        assertEquals(
            Set.of("a", "b"),
            new HashSet<>(dao.findAllKeysByDeviceProfileId(scope.tenantId(), null)));
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void findLatestByEntityIdsAndScope_returnsLatestValuePerEntityAndKey() throws Exception {
    // [v4.3.1.2] bulk latest read across an OR-set of entities in one scope.
    TestScope scope = scope("attr_lat", "55555555-5555-5555-5555-555555555511");
    bootstrapSchema(scope.database());
    EntityId secondEntity =
        new TestEntityId(UUID.fromString("55555555-5555-5555-5555-555555555512"), EntityType.ASSET);
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        // Two writes on the first entity (overwrite => latest), one on the second entity.
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(1L, "temp", lng("temp", 1L)));
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(2L, "temp", lng("temp", 9L)));
        dao.save(
                scope.tenantId(),
                secondEntity,
                AttributeScope.SERVER_SCOPE,
                attr(3L, "hum", lng("hum", 5L)))
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // A different scope on the first entity must be excluded by the scope predicate.
        save(dao, scope, AttributeScope.CLIENT_SCOPE, attr(4L, "ignored", lng("ignored", 7L)));

        List<AttributeKvEntry> latestSync =
            dao.findLatestByEntityIdsAndScope(
                scope.tenantId(),
                List.of(scope.entityId(), secondEntity),
                AttributeScope.SERVER_SCOPE);
        assertEquals(toValueMap(latestSync), Map.of("temp", 9L, "hum", 5L));

        List<AttributeKvEntry> latestAsync =
            dao.findLatestByEntityIdsAndScopeAsync(
                    scope.tenantId(),
                    List.of(scope.entityId(), secondEntity),
                    AttributeScope.SERVER_SCOPE)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(toValueMap(latestAsync), Map.of("temp", 9L, "hum", 5L));
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void keyEscaping_roundTripsKeyWithSingleQuote() throws Exception {
    TestScope scope = scope("attr_esc", "55555555-5555-5555-5555-555555555508");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        String key = "a'b";
        save(dao, scope, AttributeScope.SERVER_SCOPE, attr(5L, key, lng(key, 8L)));

        AttributeKvEntry found =
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, key)
                .orElseThrow();
        assertEquals(8L, found.getValue());

        removeAll(dao, scope, AttributeScope.SERVER_SCOPE, List.of(key));
        assertTrue(
            dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, key)
                .isEmpty());
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void keyEscaping_adversarialKeysRoundTripWithoutInjection() throws Exception {
    TestScope scope = scope("attr_esc2", "55555555-5555-5555-5555-555555555521");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config());
      try {
        List<String> keys =
            List.of(
                "a'b",
                "a''b",
                "O'Brien's \"key\"",
                "back\\slash",
                "'; DROP TABLE entity_attributes; --",
                "' OR '1'='1",
                "中文键名",
                "emoji🔑key",
                "key with spaces");
        long ts = 1000L;
        for (String key : keys) {
          save(dao, scope, AttributeScope.SERVER_SCOPE, attr(ts, key, lng(key, ts)));
          AttributeKvEntry found =
              dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, key)
                  .orElseThrow(() -> new AssertionError("round-trip read failed for key: " + key));
          assertEquals(ts, found.getValue(), "value round-trip for key: " + key);
          ts++;
        }
        // No injection: the "DROP TABLE" key did not drop the table; every key is still present.
        assertEquals(
            keys.size(),
            dao.findAll(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE).size());
        // The DELETE predicate escapes too: each key deletes independently and exactly.
        for (String key : keys) {
          removeAll(dao, scope, AttributeScope.SERVER_SCOPE, List.of(key));
          assertTrue(
              dao.find(scope.tenantId(), scope.entityId(), AttributeScope.SERVER_SCOPE, key)
                  .isEmpty(),
              "delete failed for key: " + key);
        }
      } finally {
        dao.destroy();
      }
    }
  }

  @Test
  void concurrentSaveSameIdentity_convergesToOneRow() throws Exception {
    TestScope scope = scope("attr_conc", "55555555-5555-5555-5555-555555555509");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config();
      config.getTs().getRead().setThreads(8);
      IoTDBTableAttributesDao dao = new IoTDBTableAttributesDao(pool, config);
      ExecutorService callers = Executors.newFixedThreadPool(8);
      try {
        int writes = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<ListenableFuture<Long>> futures = new ArrayList<>();
        List<java.util.concurrent.Future<?>> submissions = new ArrayList<>();
        for (int i = 0; i < writes; i++) {
          long value = i;
          submissions.add(
              callers.submit(
                  () -> {
                    try {
                      start.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new RuntimeException(e);
                    }
                    synchronized (futures) {
                      futures.add(
                          dao.save(
                              scope.tenantId(),
                              scope.entityId(),
                              AttributeScope.SERVER_SCOPE,
                              attr(1000L + value, "temp", lng("temp", value))));
                    }
                  }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> submission : submissions) {
          submission.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        for (ListenableFuture<Long> future : futures) {
          future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        // §3.5 regression: single-JVM per-identity lock must converge to exactly one row.
        assertEquals(1, attributeRowCount(pool, scope, AttributeScope.SERVER_SCOPE, "temp"));
      } finally {
        callers.shutdownNow();
        dao.destroy();
      }
    }
  }

  // ---- helpers ----

  private void save(
      IoTDBTableAttributesDao dao,
      TestScope scope,
      AttributeScope attributeScope,
      AttributeKvEntry attribute)
      throws Exception {
    dao.save(scope.tenantId(), scope.entityId(), attributeScope, attribute)
        .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private void removeAll(
      IoTDBTableAttributesDao dao,
      TestScope scope,
      AttributeScope attributeScope,
      List<String> keys)
      throws Exception {
    for (ListenableFuture<String> future :
        dao.removeAll(scope.tenantId(), scope.entityId(), attributeScope, keys)) {
      future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  private void assertFind(
      IoTDBTableAttributesDao dao,
      TestScope scope,
      AttributeScope attributeScope,
      String key,
      long lastUpdateTs,
      DataType dataType,
      Object value) {
    AttributeKvEntry entry =
        dao.find(scope.tenantId(), scope.entityId(), attributeScope, key).orElseThrow();
    assertEquals(key, entry.getKey());
    assertEquals(dataType, entry.getDataType());
    assertEquals(value, entry.getValue());
    assertEquals(lastUpdateTs, entry.getLastUpdateTs());
    assertNull(entry.getVersion());
  }

  private Map<String, Object> toValueMap(List<AttributeKvEntry> entries) {
    Map<String, Object> values = new HashMap<>();
    for (AttributeKvEntry entry : entries) {
      values.put(entry.getKey(), entry.getValue());
    }
    return values;
  }

  private int attributeRowCount(
      ITableSessionPool pool, TestScope scope, AttributeScope attributeScope, String key)
      throws Exception {
    try (ITableSession session = pool.getSession();
        SessionDataSet dataSet =
            session.executeQueryStatement(
                "SELECT bool_v,long_v,double_v,str_v,json_v FROM entity_attributes WHERE tenant_id='"
                    + scope.tenantId().getId()
                    + "' AND entity_type='DEVICE' AND entity_id='"
                    + scope.entityId().getId()
                    + "' AND attribute_scope='"
                    + attributeScope.name()
                    + "' AND key='"
                    + key.replace("'", "''")
                    + "'")) {
      SessionDataSet.DataIterator rows = dataSet.iterator();
      int rowCount = 0;
      while (rows.next()) {
        rowCount++;
      }
      return rowCount;
    }
  }

  private ITableSessionPool newPool(String database) {
    TableSessionPoolBuilder builder =
        new TableSessionPoolBuilder()
            .nodeUrls(List.of("127.0.0.1:" + IOTDB.getMappedPort(6667)))
            .user("root")
            .password("root")
            .maxSize(4);
    if (database != null) {
      builder.database(database);
    }
    return builder.build();
  }

  private void bootstrapSchema(String database) throws Exception {
    awaitIoTDBReady(database);

    String schema;
    try (InputStream stream =
        IoTDBTableAttributesDaoIT.class
            .getClassLoader()
            .getResourceAsStream("schema-iotdb-table.sql")) {
      schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    schema =
        schema
            .replace(
                "CREATE DATABASE IF NOT EXISTS thingsboard;",
                "CREATE DATABASE IF NOT EXISTS " + database + ";")
            .replace("USE thingsboard;", "USE " + database + ";");
    schema = schema.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "");
    try (ITableSessionPool bootstrapPool = newPool(null)) {
      try (ITableSession session = bootstrapPool.getSession()) {
        for (String statement : schema.split(";")) {
          String trimmed = statement.trim();
          if (!trimmed.isEmpty()) {
            session.executeNonQueryStatement(trimmed);
          }
        }
      }
    }
  }

  private void awaitIoTDBReady(String database) throws Exception {
    long deadlineNanos = System.nanoTime() + IOTDB_READY_TIMEOUT.toNanos();
    Exception lastFailure = null;
    while (System.nanoTime() < deadlineNanos) {
      try (ITableSessionPool bootstrapPool = newPool(null);
          ITableSession session = bootstrapPool.getSession()) {
        session.executeNonQueryStatement("CREATE DATABASE IF NOT EXISTS " + database);
        return;
      } catch (Exception e) {
        lastFailure = e;
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0) {
          break;
        }
        Thread.sleep(Math.min(IOTDB_READY_POLL_INTERVAL.toMillis(), remainingMillis));
      }
    }
    throw new IllegalStateException(
        "IoTDB did not accept table-session statements within " + IOTDB_READY_TIMEOUT, lastFailure);
  }

  private IoTDBTableConfig config() {
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getRead().setThreads(2);
    config.getAttributes().setClusterMode("disabled");
    return config;
  }

  private TestScope scope(String databasePrefix, String entityId) {
    return new TestScope(
        uniqueDatabase(databasePrefix),
        new TenantId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
        new TestEntityId(UUID.fromString(entityId), EntityType.DEVICE));
  }

  private String uniqueDatabase(String prefix) {
    // IoTDB caps database names at 64 chars; keep the per-test prefix short and append a trimmed
    // UUID so the total length stays well within the limit.
    String shortPrefix = prefix.length() > 12 ? prefix.substring(0, 12) : prefix;
    String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "tb_at_" + shortPrefix + "_" + shortUuid;
  }

  private TestAttributeKvEntry attr(long lastUpdateTs, String key, KvEntry value) {
    return new TestAttributeKvEntry(lastUpdateTs, value);
  }

  private KvEntry bool(String key, boolean value) {
    return new BooleanDataEntry(key, value);
  }

  private KvEntry lng(String key, long value) {
    return new LongDataEntry(key, value);
  }

  private KvEntry dbl(String key, double value) {
    return new DoubleDataEntry(key, value);
  }

  private KvEntry str(String key, String value) {
    return new StringDataEntry(key, value);
  }

  private KvEntry json(String key, String value) {
    return new JsonDataEntry(key, value);
  }

  private record TestScope(String database, TenantId tenantId, EntityId entityId) {}

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
