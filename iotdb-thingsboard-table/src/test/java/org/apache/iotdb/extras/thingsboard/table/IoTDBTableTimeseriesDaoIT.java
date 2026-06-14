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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseDeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the IoTDB Table Mode timeseries DAO against a real IoTDB 2.0.8 container:
 * the WRITE path (verified by reading the telemetry table back through raw table-session SQL) plus
 * the RAW (non-aggregated) READ path and the DELETE path exercised through the DAO. The
 * time-bucketed aggregation read path is not implemented and is not exercised here.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class IoTDBTableTimeseriesDaoIT {
  // Cold testcontainer first writes are slower than a warm production node, so the per-future
  // assertion timeout is generous; production throughput is covered elsewhere, not here.
  private static final int FUTURE_TIMEOUT_SECONDS = 30;
  private static final Duration IOTDB_STARTUP_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IOTDB_READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration IOTDB_READY_POLL_INTERVAL = Duration.ofMillis(500);
  private static String originalDatabaseTsType;
  private static String originalExperimentalRawOnly;

  @Container
  static final GenericContainer<?> IOTDB =
      new GenericContainer<>(DockerImageName.parse("apache/iotdb:2.0.8-standalone"))
          .withExposedPorts(6667)
          // IoTDB binds its client RPC service to dn_rpc_address (default 127.0.0.1), so it would
          // only listen on the container loopback and reject the Testcontainers port-mapped session
          // handshake ("Fail to reconnect"). Bind to all interfaces so the mapped host port works.
          .withEnv("dn_rpc_address", "0.0.0.0")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(IOTDB_STARTUP_TIMEOUT));

  @BeforeAll
  static void enableRawOnlyBackendProperties() {
    originalDatabaseTsType = System.getProperty("database.ts.type");
    originalExperimentalRawOnly = System.getProperty("iotdb.ts.experimental-raw-only");
    System.setProperty("database.ts.type", "iotdb-table");
    System.setProperty("iotdb.ts.experimental-raw-only", "true");
  }

  @AfterAll
  static void restoreRawOnlyBackendProperties() {
    restoreProperty("database.ts.type", originalDatabaseTsType);
    restoreProperty("iotdb.ts.experimental-raw-only", originalExperimentalRawOnly);
  }

  /**
   * Pins the IoTDB 2.0.8 engine behavior that a database-bound table-session pool can bootstrap a
   * not-yet-existing database. If a future IoTDB image changes that behavior, this test fails
   * before first boot breaks in production.
   */
  @Test
  void schemaBootstrapCreatesAFreshDatabaseThroughADatabaseBoundPool() throws Exception {
    String database = uniqueDatabase("fresh_boot");
    try (ITableSessionPool pool = newPool(database)) {
      try {
        IoTDBTableConfig config = config(1);
        config.setDatabase(database);
        IoTDBTableSchemaBootstrap bootstrap = new IoTDBTableSchemaBootstrap(pool, config);

        bootstrap.afterPropertiesSet();

        try (ITableSession session = pool.getSession()) {
          session.executeNonQueryStatement("USE " + database);
          try (SessionDataSet dataSet = session.executeQueryStatement("SHOW TABLES")) {
            String tableNameColumn = dataSet.getColumnNames().get(0);
            SessionDataSet.DataIterator rows = dataSet.iterator();
            List<String> tableNames = new ArrayList<>();
            while (rows.next()) {
              tableNames.add(rows.getString(tableNameColumn));
            }
            assertTrue(
                tableNames.containsAll(List.of("telemetry", "entity_attributes")),
                "schema bootstrap should create telemetry and entity_attributes tables: "
                    + tableNames);
          }
        }

        bootstrap.afterPropertiesSet();
      } finally {
        try (ITableSessionPool cleanupPool = newPool(null);
            ITableSession cleanupSession = cleanupPool.getSession()) {
          cleanupSession.executeNonQueryStatement("DROP DATABASE IF EXISTS " + database);
        } catch (Exception e) {
          // Keep cleanup failures from replacing the bootstrap failure under test.
        }
      }
    }
  }

  @Test
  void schemaBootstrap_writesAllTypesAndReadsExactlyOneField() throws Exception {
    TestScope scope =
        scope(
            "all_types",
            "33333333-3333-3333-3333-333333333301",
            "44444444-4444-4444-4444-444444444401");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(5);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        List.of(
                dao.save(
                    scope.tenantId(),
                    scope.entityId(),
                    entry(1000L, "bool", DataType.BOOLEAN, true),
                    0),
                dao.save(
                    scope.tenantId(),
                    scope.entityId(),
                    entry(1001L, "long", DataType.LONG, 42L),
                    0),
                dao.save(
                    scope.tenantId(),
                    scope.entityId(),
                    entry(1002L, "double", DataType.DOUBLE, 4.2D),
                    0),
                dao.save(
                    scope.tenantId(),
                    scope.entityId(),
                    entry(1003L, "string", DataType.STRING, "value"),
                    0),
                dao.save(
                    scope.tenantId(),
                    scope.entityId(),
                    entry(1004L, "json", DataType.JSON, "{\"v\":1}"),
                    0))
            .forEach(
                future -> {
                  try {
                    assertEquals(1, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                  } catch (Exception e) {
                    throw new AssertionError(e);
                  }
                });

        int rowCount = 0;
        for (String key : List.of("bool", "long", "double", "string", "json")) {
          rowCount += assertTelemetryRows(pool, scope, key, 1, 1);
        }
        assertEquals(5, rowCount);
      } finally {
        writer.destroy();
      }
    }
  }

  @Test
  void writesFiveHundredMixedEntriesInOneFlush() throws Exception {
    TestScope scope =
        scope(
            "mixed_batch",
            "33333333-3333-3333-3333-333333333302",
            "44444444-4444-4444-4444-444444444402");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(500);
      config.getTs().getSave().setMaxLingerMs(5000L);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        List<ListenableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
          futures.add(
              dao.save(
                  scope.tenantId(),
                  scope.entityId(),
                  entry(2000L + i, "mixed-" + i, DataType.LONG, (long) i),
                  0));
        }
        for (ListenableFuture<Integer> future : futures) {
          assertEquals(1, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        assertEquals(500, dao.stats().flushed());
        assertTelemetryRows(pool, scope, "mixed-499", 1, 1);
      } finally {
        writer.destroy();
      }
    }
  }

  @Test
  void saveThenFindAllAsync_roundTripsAllFiveTypes() throws Exception {
    TestScope scope =
        scope(
            "read_all_types",
            "33333333-3333-3333-3333-333333333304",
            "44444444-4444-4444-4444-444444444404");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(5);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        saveAll(
            dao,
            scope,
            List.of(
                entry(4000L, "bool", DataType.BOOLEAN, true),
                entry(4001L, "long", DataType.LONG, 42L),
                entry(4002L, "double", DataType.DOUBLE, 4.2D),
                entry(4003L, "string", DataType.STRING, "value"),
                entry(4004L, "json", DataType.JSON, "{\"v\":1}")));

        List<ReadTsKvQuery> queries =
            List.of(
                new BaseReadTsKvQuery("bool", 3990L, 4010L, 1, "ASC"),
                new BaseReadTsKvQuery("long", 3990L, 4010L, 1, "ASC"),
                new BaseReadTsKvQuery("double", 3990L, 4010L, 1, "ASC"),
                new BaseReadTsKvQuery("string", 3990L, 4010L, 1, "ASC"),
                new BaseReadTsKvQuery("json", 3990L, 4010L, 1, "ASC"));
        List<ReadTsKvQueryResult> results =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), queries)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertSingleEntry(results.get(0), 4000L, "bool", DataType.BOOLEAN, true);
        assertSingleEntry(results.get(1), 4001L, "long", DataType.LONG, 42L);
        assertSingleEntry(results.get(2), 4002L, "double", DataType.DOUBLE, 4.2D);
        assertSingleEntry(results.get(3), 4003L, "string", DataType.STRING, "value");
        assertSingleEntry(results.get(4), 4004L, "json", DataType.JSON, "{\"v\":1}");
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void findAllAsync_honorsOrderAndLimit() throws Exception {
    TestScope scope =
        scope(
            "read_order_limit",
            "33333333-3333-3333-3333-333333333305",
            "44444444-4444-4444-4444-444444444405");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(3);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        saveAll(
            dao,
            scope,
            List.of(
                entry(5000L, "ordered", DataType.LONG, 1L),
                entry(5001L, "ordered", DataType.LONG, 2L),
                entry(5002L, "ordered", DataType.LONG, 3L)));

        ReadTsKvQuery desc = new BaseReadTsKvQuery("ordered", 4990L, 5010L, 2, "DESC");
        ReadTsKvQuery asc = new BaseReadTsKvQuery("ordered", 4990L, 5010L, 2, "ASC");
        List<ReadTsKvQueryResult> results =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(desc, asc))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTimestamps(results.get(0), 5002L, 5001L);
        assertTimestamps(results.get(1), 5000L, 5001L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void findAllAsync_usesHalfOpenEnd() throws Exception {
    TestScope scope =
        scope(
            "read_half_open",
            "33333333-3333-3333-3333-333333333306",
            "44444444-4444-4444-4444-444444444406");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(3);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        saveAll(
            dao,
            scope,
            List.of(
                entry(6000L, "window", DataType.LONG, 1L),
                entry(6001L, "window", DataType.LONG, 2L),
                entry(6002L, "window", DataType.LONG, 3L)));

        ReadTsKvQuery query = new BaseReadTsKvQuery("window", 6000L, 6002L, 10, "ASC");
        ReadTsKvQueryResult result =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);

        assertTimestamps(result, 6000L, 6001L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void remove_deletesOnlyIdentityKeyAndHalfOpenRange() throws Exception {
    TestScope scope =
        scope(
            "remove_half_open",
            "33333333-3333-3333-3333-333333333307",
            "44444444-4444-4444-4444-444444444407");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        saveAll(
            dao,
            scope,
            List.of(
                entry(7000L, "target", DataType.LONG, 1L),
                entry(7001L, "target", DataType.LONG, 2L),
                entry(7002L, "target", DataType.LONG, 3L),
                entry(7001L, "other", DataType.LONG, 4L)));

        dao.remove(
                scope.tenantId(), scope.entityId(), new BaseDeleteTsKvQuery("target", 7000L, 7002L))
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ReadTsKvQuery target = new BaseReadTsKvQuery("target", 6990L, 7010L, 10, "ASC");
        ReadTsKvQuery other = new BaseReadTsKvQuery("other", 6990L, 7010L, 10, "ASC");
        List<ReadTsKvQueryResult> results =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(target, other))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTimestamps(results.get(0), 7002L);
        assertTimestamps(results.get(1), 7001L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  /**
   * Pins the documented current-scope limitation (module README "Known limitations"): a save of the
   * same (tenant, entity, key, timestamp) with a different value type in a <em>separate</em> flush
   * can leave two typed columns on that one row, and a raw read of that point then fails fast with
   * the single-typed-column invariant exception ({@link IllegalStateException} from {@link
   * IoTDBTableBaseDao#getEntry}) rather than returning wrong data. {@code batchSize = 1} forces
   * each save into its own flush so the LONG and the same-timestamp STRING are written by two
   * distinct tablets. The delete-then-insert overwrite is outside the current scope; this test
   * documents and locks the honest fail-fast behavior.
   */
  @Test
  void sameTimestampTypeChangeAcrossFlushes_readFailsFast() throws Exception {
    TestScope scope =
        scope(
            "type_change",
            "33333333-3333-3333-3333-333333333309",
            "44444444-4444-4444-4444-444444444409");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      // batchSize = 1 => every save flushes on its own, so the two writes land in two separate
      // flushes/tablets at the same (tenant, entity, key, timestamp).
      IoTDBTableConfig config = config(1);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        long ts = 9000L;
        String key = "type_change";

        // Flush 1: LONG at ts.
        assertEquals(
            1,
            dao.save(scope.tenantId(), scope.entityId(), entry(ts, key, DataType.LONG, 7L), 0)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // Flush 2: STRING at the SAME ts. Lands in long_v vs str_v on the same row.
        assertEquals(
            1,
            dao.save(scope.tenantId(), scope.entityId(), entry(ts, key, DataType.STRING, "x"), 0)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Confirm the row now actually carries two typed columns (the poisoned state).
        assertTelemetryRows(pool, scope, key, 1, 2);

        // A raw read of that point must fail fast with the single-typed-column invariant, not
        // return a value.
        ReadTsKvQuery query = new BaseReadTsKvQuery(key, ts - 10L, ts + 10L, 10, "ASC");
        ExecutionException failure =
            assertThrows(
                ExecutionException.class,
                () ->
                    dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
                        .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Throwable cause = failure.getCause();
        assertTrue(
            cause instanceof IllegalStateException,
            "raw read of a poisoned point must fail with IllegalStateException, was: " + cause);
        assertTrue(
            cause.getMessage() != null && cause.getMessage().contains("typed value columns set"),
            "fail-fast message should name the single-typed-column invariant: "
                + cause.getMessage());
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void keyEscaping_roundTrip() throws Exception {
    TestScope scope =
        scope(
            "key_escape",
            "33333333-3333-3333-3333-333333333308",
            "44444444-4444-4444-4444-444444444408");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        String key = "a'b";
        saveAll(dao, scope, List.of(entry(8000L, key, DataType.LONG, 8L)));

        ReadTsKvQuery query = new BaseReadTsKvQuery(key, 7990L, 8010L, 10, "ASC");
        ReadTsKvQueryResult readBeforeDelete =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);
        assertTimestamps(readBeforeDelete, 8000L);

        dao.remove(scope.tenantId(), scope.entityId(), new BaseDeleteTsKvQuery(key, 7990L, 8010L))
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ReadTsKvQueryResult readAfterDelete =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);
        assertTrue(readAfterDelete.getData().isEmpty());
      } finally {
        dao.destroy();
        writer.destroy();
      }
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
        IoTDBTableTimeseriesDaoIT.class
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

  private IoTDBTableConfig config(int batchSize) {
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().setExperimentalRawOnly(true);
    config.getTs().getSave().setBatchSize(batchSize);
    config.getTs().getSave().setMaxLingerMs(20L);
    config.getTs().getSave().setRetryInitialBackoffMs(1L);
    config.getTs().getSave().setRetryMaxBackoffMs(1L);
    return config;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private TestScope scope(String databasePrefix, String tenantId, String entityId) {
    return new TestScope(
        uniqueDatabase(databasePrefix),
        new TenantId(UUID.fromString(tenantId)),
        new TestEntityId(UUID.fromString(entityId), EntityType.DEVICE));
  }

  private String uniqueDatabase(String prefix) {
    // IoTDB caps database names at 64 chars; keep the per-test prefix short and
    // append a trimmed UUID so the total length stays well within the limit.
    String shortPrefix = prefix.length() > 12 ? prefix.substring(0, 12) : prefix;
    String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "tb_it_" + shortPrefix + "_" + shortUuid;
  }

  private int assertTelemetryRows(
      ITableSessionPool pool,
      TestScope scope,
      String key,
      int expectedRows,
      int expectedTypedFields)
      throws Exception {
    try (ITableSession session = pool.getSession();
        SessionDataSet dataSet =
            session.executeQueryStatement(
                "SELECT bool_v,long_v,double_v,str_v,json_v FROM telemetry "
                    + telemetryWhere(scope, key))) {
      SessionDataSet.DataIterator rows = dataSet.iterator();
      int rowCount = 0;
      while (rows.next()) {
        assertEquals(expectedTypedFields, typedFieldCount(rows));
        rowCount++;
      }
      assertEquals(expectedRows, rowCount);
      return rowCount;
    }
  }

  private String telemetryWhere(TestScope scope, String key) {
    return "WHERE tenant_id='"
        + scope.tenantId().getId()
        + "' AND entity_type='DEVICE' AND entity_id='"
        + scope.entityId().getId()
        + "' AND key='"
        + key
        + "'";
  }

  private int typedFieldCount(SessionDataSet.DataIterator row) throws Exception {
    int count = 0;
    count += row.isNull("bool_v") ? 0 : 1;
    count += row.isNull("long_v") ? 0 : 1;
    count += row.isNull("double_v") ? 0 : 1;
    count += row.isNull("str_v") ? 0 : 1;
    count += row.isNull("json_v") ? 0 : 1;
    return count;
  }

  private TestTsKvEntry entry(long ts, String key, DataType dataType, Object value) {
    return new TestTsKvEntry(ts, key, dataType, value);
  }

  private void saveAll(IoTDBTableTimeseriesDao dao, TestScope scope, List<TestTsKvEntry> entries)
      throws Exception {
    List<ListenableFuture<Integer>> futures = new ArrayList<>();
    for (TestTsKvEntry entry : entries) {
      futures.add(dao.save(scope.tenantId(), scope.entityId(), entry, 0));
    }
    for (ListenableFuture<Integer> future : futures) {
      assertEquals(1, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
  }

  private void assertSingleEntry(
      ReadTsKvQueryResult result, long ts, String key, DataType dataType, Object value) {
    assertEquals(1, result.getData().size());
    TsKvEntry entry = result.getData().get(0);
    assertEquals(ts, entry.getTs());
    assertEquals(key, entry.getKey());
    assertEquals(dataType, entry.getDataType());
    assertEquals(value, entry.getValue());
    assertEquals(String.valueOf(value), entry.getValueAsString());
    assertEquals(ts, result.getLastEntryTs());
  }

  private void assertTimestamps(ReadTsKvQueryResult result, long... expectedTs) {
    assertEquals(expectedTs.length, result.getData().size());
    for (int i = 0; i < expectedTs.length; i++) {
      assertEquals(expectedTs[i], result.getData().get(i).getTs());
    }
    if (expectedTs.length > 0) {
      long maxTs = expectedTs[0];
      for (long ts : expectedTs) {
        maxTs = Math.max(maxTs, ts);
      }
      assertEquals(maxTs, result.getLastEntryTs());
    }
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
