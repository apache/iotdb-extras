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
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import com.google.common.util.concurrent.ListenableFuture;
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
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class IoTDBTableLatestDaoIT {
  // Cold testcontainer first writes/reads are slower than a warm production node, so the
  // per-future assertion timeout is generous; production throughput is covered elsewhere.
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
  void saveThenFindLatest_roundTripsAllFiveTypes() throws Exception {
    TestScope scope =
        scope(
            "latest_types",
            "55555555-5555-5555-5555-555555555501",
            "66666666-6666-6666-6666-666666666601");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(5);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // Two rows per key at ascending timestamps; the latest read must return the newer value.
        saveAll(
            tsDao,
            scope,
            List.of(
                entry(1000L, "bool", DataType.BOOLEAN, false),
                entry(1010L, "bool", DataType.BOOLEAN, true),
                entry(1000L, "long", DataType.LONG, 1L),
                entry(1010L, "long", DataType.LONG, 42L),
                entry(1000L, "double", DataType.DOUBLE, 1.0D),
                entry(1010L, "double", DataType.DOUBLE, 4.2D),
                entry(1000L, "string", DataType.STRING, "old"),
                entry(1010L, "string", DataType.STRING, "value"),
                entry(1000L, "json", DataType.JSON, "{\"v\":0}"),
                entry(1010L, "json", DataType.JSON, "{\"v\":1}")));

        assertLatest(latestDao, scope, "bool", 1010L, DataType.BOOLEAN, true);
        assertLatest(latestDao, scope, "long", 1010L, DataType.LONG, 42L);
        assertLatest(latestDao, scope, "double", 1010L, DataType.DOUBLE, 4.2D);
        assertLatest(latestDao, scope, "string", 1010L, DataType.STRING, "value");
        assertLatest(latestDao, scope, "json", 1010L, DataType.JSON, "{\"v\":1}");
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void findAllLatest_returnsLatestPerKeyWithoutTypeBackfill() throws Exception {
    TestScope scope =
        scope(
            "latest_all",
            "55555555-5555-5555-5555-555555555502",
            "66666666-6666-6666-6666-666666666602");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // "mixed" changes type over time: long_v@2000 then str_v@2010. LAST_BY must return only the
        // newer string value and must NOT backfill the older long column into the aggregated row.
        saveAll(
            tsDao,
            scope,
            List.of(
                entry(2000L, "mixed", DataType.LONG, 7L),
                entry(2010L, "mixed", DataType.STRING, "latest"),
                entry(2000L, "temperature", DataType.DOUBLE, 20.0D),
                entry(2005L, "temperature", DataType.DOUBLE, 21.5D),
                entry(2000L, "online", DataType.BOOLEAN, true)));

        List<TsKvEntry> latest =
            latestDao
                .findAllLatest(scope.tenantId(), scope.entityId())
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        latest.sort(Comparator.comparing(TsKvEntry::getKey));

        // Sorted by key, so the order is: mixed, online, temperature.
        assertEquals(3, latest.size());
        assertEntry(latest.get(0), 2010L, "mixed", DataType.STRING, "latest");
        assertEntry(latest.get(1), 2000L, "online", DataType.BOOLEAN, true);
        assertEntry(latest.get(2), 2005L, "temperature", DataType.DOUBLE, 21.5D);
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void findLatest_emptyReturnsNullStringEntryFallback() throws Exception {
    TestScope scope =
        scope(
            "latest_empty",
            "55555555-5555-5555-5555-555555555503",
            "66666666-6666-6666-6666-666666666603");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        Optional<TsKvEntry> opt =
            latestDao
                .findLatestOpt(scope.tenantId(), scope.entityId(), "never-written")
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(opt.isEmpty());

        TsKvEntry fallback =
            latestDao
                .findLatest(scope.tenantId(), scope.entityId(), "never-written")
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("never-written", fallback.getKey());
        assertEquals(DataType.STRING, fallback.getDataType());
        assertNull(fallback.getValue());
      } finally {
        latestDao.destroy();
      }
    }
  }

  @Test
  void findLatest_failsOnCrossBatchSameTimestampTypeChange() throws Exception {
    TestScope scope =
        scope(
            "latest_stale",
            "55555555-5555-5555-5555-555555555505",
            "66666666-6666-6666-6666-666666666605");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // Cross-batch same-timestamp type change leaves two typed columns set at time=4000 (the
        // documented Phase-1 limitation). The derived DESC-LIMIT-1 latest read must fail-fast.
        assertEquals(
            1,
            tsDao
                .save(
                    scope.tenantId(), scope.entityId(), entry(4000L, "flip", DataType.LONG, 7L), 0)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(
            1,
            tsDao
                .save(
                    scope.tenantId(),
                    scope.entityId(),
                    entry(4000L, "flip", DataType.STRING, "seven"),
                    0)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        ExecutionException failure =
            assertThrows(
                ExecutionException.class,
                () ->
                    latestDao
                        .findLatest(scope.tenantId(), scope.entityId(), "flip")
                        .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("2 typed value columns set"));
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  private void assertLatest(
      IoTDBTableLatestDao latestDao,
      TestScope scope,
      String key,
      long expectedTs,
      DataType dataType,
      Object value)
      throws Exception {
    Optional<TsKvEntry> opt =
        latestDao
            .findLatestOpt(scope.tenantId(), scope.entityId(), key)
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(opt.isPresent(), "expected a latest value for key " + key);
    assertEntry(opt.get(), expectedTs, key, dataType, value);

    TsKvEntry present =
        latestDao
            .findLatest(scope.tenantId(), scope.entityId(), key)
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEntry(present, expectedTs, key, dataType, value);
  }

  private void assertEntry(
      TsKvEntry entry, long expectedTs, String key, DataType dataType, Object value) {
    assertEquals(expectedTs, entry.getTs());
    assertEquals(key, entry.getKey());
    assertEquals(dataType, entry.getDataType());
    assertEquals(value, entry.getValue());
    assertEquals(String.valueOf(value), entry.getValueAsString());
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
        IoTDBTableLatestDaoIT.class
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
    config.getTs().getSave().setBatchSize(batchSize);
    config.getTs().getSave().setMaxLingerMs(20L);
    config.getTs().getSave().setRetryInitialBackoffMs(1L);
    config.getTs().getSave().setRetryMaxBackoffMs(1L);
    config.getTs().getRead().setThreads(1);
    return config;
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

  private TestScope scope(String databasePrefix, String tenantId, String entityId) {
    return new TestScope(
        uniqueDatabase(databasePrefix),
        new TenantId(UUID.fromString(tenantId)),
        new TestEntityId(UUID.fromString(entityId), EntityType.DEVICE));
  }

  private String uniqueDatabase(String prefix) {
    // IoTDB caps database names at 64 chars; keep the per-test prefix short and append a trimmed
    // UUID so the total length stays well within the limit.
    String shortPrefix = prefix.length() > 12 ? prefix.substring(0, 12) : prefix;
    String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "tb_lt_" + shortPrefix + "_" + shortUuid;
  }

  private TestTsKvEntry entry(long ts, String key, DataType dataType, Object value) {
    return new TestTsKvEntry(ts, key, dataType, value);
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
