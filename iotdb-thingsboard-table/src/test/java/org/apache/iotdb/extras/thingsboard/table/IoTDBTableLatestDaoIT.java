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
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    bootstrapLatestSchema(scope.database());
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
    bootstrapLatestSchema(scope.database());
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
    bootstrapLatestSchema(scope.database());
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
    bootstrapLatestSchema(scope.database());
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

  // ---- latest overlay (telemetry_latest): second bootstrap, saveLatest, merge, removeLatest ----

  @Test
  void latestOverlayBootstrap_createsTelemetryLatestTable() throws Exception {
    TestScope scope =
        scope(
            "lt_boot",
            "55555555-5555-5555-5555-555555555510",
            "66666666-6666-6666-6666-666666666610");
    bootstrapSchema(scope.database());
    // The SECOND bootstrap (schema-iotdb-table-latest.sql via the 3-arg ctor) creates the overlay.
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      // Querying telemetry_latest must succeed (table exists); it is empty until a saveLatest.
      try (ITableSession session = pool.getSession();
          SessionDataSet dataSet =
              session.executeQueryStatement("SELECT key FROM telemetry_latest")) {
        assertFalse(dataSet.iterator().next(), "telemetry_latest should exist and be empty");
      }
      // Idempotency: a second bootstrap run must not throw.
      bootstrapLatestSchema(scope.database());
    }
  }

  @Test
  void withoutLatestBootstrap_telemetryLatestTableIsNotCreated() throws Exception {
    // The latest overlay DDL lives in a SEPARATE resource gated by the latest selector; the base
    // bootstrap alone must NOT create telemetry_latest (latest-off => overlay table absent).
    TestScope scope =
        scope(
            "lt_off",
            "55555555-5555-5555-5555-555555555511",
            "66666666-6666-6666-6666-666666666611");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database());
        ITableSession session = pool.getSession()) {
      assertThrows(
          Exception.class, () -> session.executeQueryStatement("SELECT key FROM telemetry_latest"));
    }
  }

  @Test
  void latestOnlySaveLatest_thenFindLatest_roundTripsWithoutPairedSave() throws Exception {
    // Load-bearing EntityView LATEST_AND_WS case: saveLatest is called with NO paired tsDao.save(),
    // so nothing writes the telemetry table. The overlay must capture the value and findLatest must
    // return it (the no-shadow-table no-op would have dropped it).
    TestScope scope =
        scope(
            "lt_only",
            "55555555-5555-5555-5555-555555555512",
            "66666666-6666-6666-6666-666666666612");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        saveLatest(latestDao, scope, entry(1000L, "bool", DataType.BOOLEAN, true));
        saveLatest(latestDao, scope, entry(1000L, "long", DataType.LONG, 42L));
        saveLatest(latestDao, scope, entry(1000L, "double", DataType.DOUBLE, 4.2D));
        saveLatest(latestDao, scope, entry(1000L, "string", DataType.STRING, "value"));
        saveLatest(latestDao, scope, entry(1000L, "json", DataType.JSON, "{\"v\":1}"));

        assertLatest(latestDao, scope, "bool", 1000L, DataType.BOOLEAN, true);
        assertLatest(latestDao, scope, "long", 1000L, DataType.LONG, 42L);
        assertLatest(latestDao, scope, "double", 1000L, DataType.DOUBLE, 4.2D);
        assertLatest(latestDao, scope, "string", 1000L, DataType.STRING, "value");
        assertLatest(latestDao, scope, "json", 1000L, DataType.JSON, "{\"v\":1}");

        // Overwrite is delete-then-insert: a single overlay row survives, holding the newer value.
        saveLatest(latestDao, scope, entry(2000L, "long", DataType.LONG, 99L));
        assertLatest(latestDao, scope, "long", 2000L, DataType.LONG, 99L);
        assertEquals(1, overlayRowCount(pool, scope, "long"));
      } finally {
        latestDao.destroy();
      }
    }
  }

  @Test
  void findLatest_mergesTelemetryAndOverlayByMaxTs() throws Exception {
    TestScope scope =
        scope(
            "lt_merge",
            "55555555-5555-5555-5555-555555555513",
            "66666666-6666-6666-6666-666666666613");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(2);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // k_over: telemetry@1000 then a NEWER overlay@2000 -> overlay wins.
        saveAll(tsDao, scope, List.of(entry(1000L, "k_over", DataType.LONG, 1L)));
        saveLatest(latestDao, scope, entry(2000L, "k_over", DataType.LONG, 99L));
        assertLatest(latestDao, scope, "k_over", 2000L, DataType.LONG, 99L);

        // k_deriv: overlay@2000 then a NEWER telemetry@3000 -> derived wins.
        saveLatest(latestDao, scope, entry(2000L, "k_deriv", DataType.LONG, 5L));
        saveAll(tsDao, scope, List.of(entry(3000L, "k_deriv", DataType.LONG, 7L)));
        assertLatest(latestDao, scope, "k_deriv", 3000L, DataType.LONG, 7L);
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void findAllLatest_mixesDerivedAndOverlayOnlyKeys() throws Exception {
    TestScope scope =
        scope(
            "lt_all_mix",
            "55555555-5555-5555-5555-555555555514",
            "66666666-6666-6666-6666-666666666614");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // derived-only key, overlay-only key, and a key present in both (overlay newer -> wins).
        saveAll(
            tsDao,
            scope,
            List.of(
                entry(1000L, "derived", DataType.DOUBLE, 1.5D),
                entry(1000L, "both", DataType.LONG, 1L)));
        saveLatest(latestDao, scope, entry(2000L, "overlay", DataType.STRING, "ov"));
        saveLatest(latestDao, scope, entry(2000L, "both", DataType.LONG, 9L));

        List<TsKvEntry> latest =
            latestDao
                .findAllLatest(scope.tenantId(), scope.entityId())
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        latest.sort(Comparator.comparing(TsKvEntry::getKey));

        assertEquals(3, latest.size());
        assertEntry(latest.get(0), 2000L, "both", DataType.LONG, 9L);
        assertEntry(latest.get(1), 1000L, "derived", DataType.DOUBLE, 1.5D);
        assertEntry(latest.get(2), 2000L, "overlay", DataType.STRING, "ov");
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void removeLatest_ofOverlayValue_marksRemovedAndClearsOverlay() throws Exception {
    TestScope scope =
        scope(
            "lt_rm",
            "55555555-5555-5555-5555-555555555515",
            "66666666-6666-6666-6666-666666666615");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        saveLatest(latestDao, scope, entry(1500L, "k", DataType.LONG, 7L));
        assertLatest(latestDao, scope, "k", 1500L, DataType.LONG, 7L);

        TsKvLatestRemovingResult result =
            latestDao
                .removeLatest(
                    scope.tenantId(), scope.entityId(), new BaseDeleteTsKvQuery("k", 1000L, 2000L))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.isRemoved());
        assertNull(result.getVersion());

        // Overlay row deleted and (no telemetry) findLatest is now empty.
        assertEquals(0, overlayRowCount(pool, scope, "k"));
        assertTrue(
            latestDao
                .findLatestOpt(scope.tenantId(), scope.entityId(), "k")
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .isEmpty());
      } finally {
        latestDao.destroy();
      }
    }
  }

  @Test
  void removeLatest_rewriteResurrectsPriorHistoryIntoOverlay() throws Exception {
    TestScope scope =
        scope(
            "lt_rw",
            "55555555-5555-5555-5555-555555555516",
            "66666666-6666-6666-6666-666666666616");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(2);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // History: a prior value@1000 (outside the delete window) and the in-window latest@1500.
        saveAll(
            tsDao,
            scope,
            List.of(entry(1000L, "k", DataType.LONG, 10L), entry(1500L, "k", DataType.LONG, 20L)));
        assertLatest(latestDao, scope, "k", 1500L, DataType.LONG, 20L);

        // Delete window [1200, 2000) covers the latest@1500 but not the prior@1000; rewrite=true.
        TsKvLatestRemovingResult result =
            latestDao
                .removeLatest(
                    scope.tenantId(),
                    scope.entityId(),
                    new BaseDeleteTsKvQuery("k", 1200L, 2000L, true))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // The removing result carries the resurrected prior value (WS UPDATE, not DELETE).
        assertTrue(result.isRemoved());
        assertNull(result.getVersion());
        assertNotNull(result.getData());
        assertEquals(1000L, result.getData().getTs());
        assertEquals(DataType.LONG, result.getData().getDataType());
        assertEquals(10L, result.getData().getValue());

        // The prior was written back into the overlay (one row, holding 1000/10). The in-window
        // telemetry row is NOT deleted by this DAO (TB's historical remove is a separate path), so
        // the derived read still sees 1500 -- assert the overlay write-back directly.
        assertEquals(1, overlayRowCount(pool, scope, "k"));
        OverlayRow overlay = overlayRow(pool, scope, "k");
        assertEquals(1000L, overlay.ts());
        assertEquals(10L, overlay.longValue());
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void saveLatest_outOfOrderDoesNotRegressOverlayOnlyLatest() throws Exception {
    TestScope scope =
        scope(
            "lt_ooo",
            "55555555-5555-5555-5555-555555555517",
            "66666666-6666-6666-6666-666666666617");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // Overlay-only key (no paired tsDao.save()): saveLatest@200 then a BACKDATED
        // saveLatest@100.
        // Max-ts-wins: the out-of-order write must NOT regress the latest below the stored 200.
        saveLatest(latestDao, scope, entry(200L, "k", DataType.LONG, 20L));
        saveLatest(latestDao, scope, entry(100L, "k", DataType.LONG, 10L));
        assertLatest(latestDao, scope, "k", 200L, DataType.LONG, 20L);
        // The backdated write was skipped, so a single overlay row (the 200 value) survives.
        assertEquals(1, overlayRowCount(pool, scope, "k"));

        // A forward saveLatest@300 is newer than the stored 200 and MUST win.
        saveLatest(latestDao, scope, entry(300L, "k", DataType.LONG, 30L));
        assertLatest(latestDao, scope, "k", 300L, DataType.LONG, 30L);
      } finally {
        latestDao.destroy();
      }
    }
  }

  @Test
  void removeLatest_inWindowDerivedKeepsOutOfWindowOverlay() throws Exception {
    TestScope scope =
        scope(
            "lt_oow",
            "55555555-5555-5555-5555-555555555518",
            "66666666-6666-6666-6666-666666666618");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(2);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // Overlay holds an out-of-window value @50; a telemetry-only write @150 (no saveLatest) is
        // the in-window derived latest. removeLatest[100,200) removes the in-window latest but must
        // NOT wipe the out-of-window overlay @50 (a tag-only all-time delete would). The overlay
        // value survives as the next latest once TB's separate historical path removes telemetry.
        saveLatest(latestDao, scope, entry(50L, "k", DataType.LONG, 5L));
        saveAll(tsDao, scope, List.of(entry(150L, "k", DataType.LONG, 15L)));

        TsKvLatestRemovingResult result =
            latestDao
                .removeLatest(
                    scope.tenantId(), scope.entityId(), new BaseDeleteTsKvQuery("k", 100L, 200L))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.isRemoved());

        // The out-of-window overlay row survived.
        assertEquals(1, overlayRowCount(pool, scope, "k"));
        OverlayRow overlay = overlayRow(pool, scope, "k");
        assertEquals(50L, overlay.ts());
        assertEquals(5L, overlay.longValue());
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void removeLatest_rewritePrefersNewerOverlayOverOlderHistory() throws Exception {
    TestScope scope =
        scope(
            "lt_rwo",
            "55555555-5555-5555-5555-555555555519",
            "66666666-6666-6666-6666-666666666619");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(2);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // Out-of-window overlay value @50 plus an older telemetry history @30, with the in-window
        // derived latest @150. rewrite must resurrect the NEWEST next-older across BOTH stores: the
        // overlay @50, not the telemetry history @30 (telemetry-only lookup would pick 30).
        saveLatest(latestDao, scope, entry(50L, "k", DataType.LONG, 5L));
        saveAll(
            tsDao,
            scope,
            List.of(entry(30L, "k", DataType.LONG, 3L), entry(150L, "k", DataType.LONG, 15L)));

        TsKvLatestRemovingResult result =
            latestDao
                .removeLatest(
                    scope.tenantId(),
                    scope.entityId(),
                    new BaseDeleteTsKvQuery("k", 100L, 200L, true))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(result.isRemoved());
        assertNotNull(result.getData());
        assertEquals(50L, result.getData().getTs());
        assertEquals(5L, result.getData().getValue());
        OverlayRow overlay = overlayRow(pool, scope, "k");
        assertEquals(50L, overlay.ts());
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void keyEscaping_adversarialKeysRoundTripWithoutInjection() throws Exception {
    TestScope scope =
        scope(
            "lt_esc",
            "55555555-5555-5555-5555-555555555520",
            "66666666-6666-6666-6666-666666666620");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(1);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        List<String> keys =
            List.of(
                "a'b",
                "a''b",
                "O'Brien's \"key\"",
                "back\\slash",
                "'; DROP TABLE telemetry_latest; --",
                "' OR '1'='1",
                "中文键名",
                "emoji🔑key",
                "key with spaces");
        long ts = 1000L;
        for (String key : keys) {
          saveLatest(latestDao, scope, entry(ts, key, DataType.LONG, ts));
          assertLatest(latestDao, scope, key, ts, DataType.LONG, ts);
          ts++;
        }
        // No injection: each key's latest is removable independently (escaped DELETE predicate),
        // and
        // the "DROP TABLE" key never dropped the overlay table.
        long t = 1000L;
        for (String key : keys) {
          TsKvLatestRemovingResult result =
              latestDao
                  .removeLatest(
                      scope.tenantId(), scope.entityId(), new BaseDeleteTsKvQuery(key, 0L, t + 1))
                  .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          assertTrue(result.isRemoved(), "removeLatest failed for key: " + key);
          assertTrue(
              latestDao
                  .findLatestOpt(scope.tenantId(), scope.entityId(), key)
                  .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  .isEmpty(),
              "delete left a value for key: " + key);
          t++;
        }
      } finally {
        latestDao.destroy();
      }
    }
  }

  @Test
  void concurrentSaveLatestSameIdentity_convergesToOneRowMaxTsWins() throws Exception {
    TestScope scope =
        scope(
            "lt_conc",
            "55555555-5555-5555-5555-555555555522",
            "66666666-6666-6666-6666-666666666622");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
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
                          latestDao.saveLatest(
                              scope.tenantId(),
                              scope.entityId(),
                              entry(1000L + value, "temp", DataType.LONG, value)));
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

        // Per-identity lock + delete-then-insert converge to exactly one overlay row, and the
        // max-ts
        // guard makes the highest timestamp win regardless of the concurrent interleaving.
        assertEquals(1, overlayRowCount(pool, scope, "temp"));
        assertLatest(latestDao, scope, "temp", 1049L, DataType.LONG, 49L);
      } finally {
        callers.shutdownNow();
        latestDao.destroy();
      }
    }
  }

  @Test
  void findAllKeysByEntityIds_returnsDistinctKeysForEntities() throws Exception {
    TestScope scope =
        scope(
            "latest_keys",
            "55555555-5555-5555-5555-555555555504",
            "66666666-6666-6666-6666-666666666604");
    bootstrapSchema(scope.database());
    // Key discovery now also reads the telemetry_latest overlay, so the overlay table must exist.
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(6);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        saveAll(
            tsDao,
            scope,
            List.of(
                entry(3000L, "temperature", DataType.DOUBLE, 1.0D),
                entry(3001L, "temperature", DataType.DOUBLE, 2.0D),
                entry(3000L, "humidity", DataType.LONG, 50L),
                entry(3000L, "status", DataType.STRING, "ok")));

        List<String> keys =
            latestDao.findAllKeysByEntityIds(scope.tenantId(), List.of(scope.entityId()));
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.naturalOrder());
        assertEquals(List.of("humidity", "status", "temperature"), sorted);

        List<String> async =
            latestDao
                .findAllKeysByEntityIdsAsync(scope.tenantId(), List.of(scope.entityId()))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        async.sort(Comparator.naturalOrder());
        assertEquals(List.of("humidity", "status", "temperature"), async);
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void keyDiscovery_scopesDistinctKeysByEntitySetTenantAndDefersDeviceProfile() throws Exception {
    TestScope scope =
        scope(
            "latest_kscope",
            "55555555-5555-5555-5555-555555555510",
            "66666666-6666-6666-6666-666666666610");
    // A second entity under the SAME tenant with a partly-overlapping key set, and an entity under
    // a
    // DIFFERENT tenant whose keys must never leak into the first tenant's discovery.
    EntityId secondEntity =
        new TestEntityId(UUID.fromString("66666666-6666-6666-6666-666666666611"), EntityType.ASSET);
    TenantId otherTenant = new TenantId(UUID.fromString("55555555-5555-5555-5555-555555555599"));
    EntityId otherTenantEntity =
        new TestEntityId(
            UUID.fromString("66666666-6666-6666-6666-666666666699"), EntityType.DEVICE);
    bootstrapSchema(scope.database());
    // Key discovery now also reads the telemetry_latest overlay, so the overlay table must exist.
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // Entity 1 (DEVICE): temperature, humidity. Entity 2 (ASSET, same tenant): humidity, power.
        // Other-tenant entity: leaked (must be excluded by tenant scoping).
        saveOne(tsDao, scope.tenantId(), scope.entityId(), 7000L, "temperature", 1.0D);
        saveOne(tsDao, scope.tenantId(), scope.entityId(), 7000L, "humidity", 40L);
        saveOne(tsDao, scope.tenantId(), secondEntity, 7000L, "humidity", 41L);
        saveOne(tsDao, scope.tenantId(), secondEntity, 7000L, "power", 9.9D);
        saveOne(tsDao, otherTenant, otherTenantEntity, 7000L, "leaked", 7L);

        // Entity-set union across both same-tenant entities -> deduplicated humidity.
        assertEquals(
            List.of("humidity", "power", "temperature"),
            sorted(
                latestDao.findAllKeysByEntityIds(
                    scope.tenantId(), List.of(scope.entityId(), secondEntity))));

        // Single-entity scope returns only that entity's keys.
        assertEquals(
            List.of("humidity", "temperature"),
            sorted(latestDao.findAllKeysByEntityIds(scope.tenantId(), List.of(scope.entityId()))));
        assertEquals(
            List.of("humidity", "power"),
            sorted(latestDao.findAllKeysByEntityIds(scope.tenantId(), List.of(secondEntity))));

        // Null deviceProfileId -> tenant-wide distinct keys, never crossing the tenant boundary.
        List<String> tenantWide =
            sorted(latestDao.findAllKeysByDeviceProfileId(scope.tenantId(), null));
        assertEquals(List.of("humidity", "power", "temperature"), tenantWide);
        assertTrue(!tenantWide.contains("leaked"), "tenant scoping must exclude other-tenant keys");

        // Non-null deviceProfileId stays deferred (telemetry table has no device_profile_id tag);
        // the structural deferral returns no keys rather than faking profile membership.
        assertEquals(
            List.of(),
            latestDao.findAllKeysByDeviceProfileId(
                scope.tenantId(),
                new org.thingsboard.server.common.data.id.DeviceProfileId(
                    UUID.fromString("44444444-4444-4444-4444-444444444444"))));
      } finally {
        latestDao.destroy();
        tsDao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void findAllKeys_includesOverlayOnlyLatestKey() throws Exception {
    // Regression: a latest-only key written via saveLatest with NO paired tsDao.save() lives ONLY
    // in
    // the telemetry_latest overlay. Key discovery unions telemetry + the overlay, so that
    // overlay-only key must surface in BOTH findAllKeysByEntityIds AND the tenant-wide
    // findAllKeysByDeviceProfileId(null) path (otherwise key discovery would return a narrower key
    // universe than findAllLatest).
    TestScope scope =
        scope(
            "lt_keys_ov",
            "55555555-5555-5555-5555-555555555518",
            "66666666-6666-6666-6666-666666666618");
    bootstrapSchema(scope.database());
    bootstrapLatestSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(2);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao tsDao = new IoTDBTableTimeseriesDao(pool, writer, config);
      IoTDBTableLatestDao latestDao = new IoTDBTableLatestDao(pool, config);
      try {
        // "historical" lands in telemetry; "overlayOnly" is written via saveLatest with no save().
        saveAll(tsDao, scope, List.of(entry(1000L, "historical", DataType.DOUBLE, 1.0D)));
        saveLatest(latestDao, scope, entry(2000L, "overlayOnly", DataType.LONG, 9L));

        // Entity-set key discovery unions both tables -> the overlay-only key is present.
        assertEquals(
            List.of("historical", "overlayOnly"),
            sorted(latestDao.findAllKeysByEntityIds(scope.tenantId(), List.of(scope.entityId()))));

        // Tenant-wide (null deviceProfileId) discovery also unions both tables.
        assertEquals(
            List.of("historical", "overlayOnly"),
            sorted(latestDao.findAllKeysByDeviceProfileId(scope.tenantId(), null)));
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

  private void bootstrapLatestSchema(String database) throws Exception {
    // Exercise the REAL production bootstrap path: the 3-arg IoTDBTableSchemaBootstrap loading the
    // telemetry_latest overlay resource. The bootstrap pool has no fixed database (CREATE DATABASE
    // runs first), and the config carries the target database so the DDL is rewritten onto it.
    awaitIoTDBReady(database);
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.setDatabase(database);
    try (ITableSessionPool bootstrapPool = newPool(null)) {
      new IoTDBTableSchemaBootstrap(
              bootstrapPool, config, IoTDBTableSchemaBootstrap.LATEST_SCHEMA_RESOURCE)
          .afterPropertiesSet();
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
    config.getTsLatest().setClusterMode("disabled");
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

  private void saveOne(
      IoTDBTableTimeseriesDao dao,
      TenantId tenantId,
      EntityId entityId,
      long ts,
      String key,
      Object value)
      throws Exception {
    DataType dataType =
        value instanceof Long
            ? DataType.LONG
            : value instanceof Double ? DataType.DOUBLE : DataType.STRING;
    assertEquals(
        1,
        dao.save(tenantId, entityId, new TestTsKvEntry(ts, key, dataType, value), 0)
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  private List<String> sorted(List<String> keys) {
    List<String> copy = new ArrayList<>(keys);
    copy.sort(Comparator.naturalOrder());
    return copy;
  }

  private void saveLatest(IoTDBTableLatestDao dao, TestScope scope, TestTsKvEntry entry)
      throws Exception {
    // saveLatest writes the overlay only (no paired tsDao.save()) and returns a null version.
    assertNull(
        dao.saveLatest(scope.tenantId(), scope.entityId(), entry)
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  private int overlayRowCount(ITableSessionPool pool, TestScope scope, String key)
      throws Exception {
    try (ITableSession session = pool.getSession();
        SessionDataSet dataSet =
            session.executeQueryStatement(
                "SELECT bool_v,long_v,double_v,str_v,json_v FROM telemetry_latest WHERE tenant_id='"
                    + scope.tenantId().getId()
                    + "' AND entity_type='DEVICE' AND entity_id='"
                    + scope.entityId().getId()
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

  private OverlayRow overlayRow(ITableSessionPool pool, TestScope scope, String key)
      throws Exception {
    try (ITableSession session = pool.getSession();
        SessionDataSet dataSet =
            session.executeQueryStatement(
                "SELECT time, long_v FROM telemetry_latest WHERE tenant_id='"
                    + scope.tenantId().getId()
                    + "' AND entity_type='DEVICE' AND entity_id='"
                    + scope.entityId().getId()
                    + "' AND key='"
                    + key.replace("'", "''")
                    + "'")) {
      SessionDataSet.DataIterator rows = dataSet.iterator();
      assertTrue(rows.next(), "expected one overlay row for key " + key);
      return new OverlayRow(rows.getTimestamp("time").getTime(), rows.getLong("long_v"));
    }
  }

  private record OverlayRow(long ts, long longValue) {}

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
