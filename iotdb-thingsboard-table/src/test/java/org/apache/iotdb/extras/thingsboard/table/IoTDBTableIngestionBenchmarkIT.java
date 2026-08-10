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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-1 ingestion-throughput benchmark for the IoTDB Table Mode timeseries write path (smoke
 * profile).
 *
 * <p>TC-1 is defined with two profiles. This class is the <b>smoke profile</b>: a local, fast,
 * JUnit-driven run that exercises the real {@link IoTDBTableTimeseriesDao#save} path (bounded queue
 * &rarr; single flush worker &rarr; multi-row {@code Tablet} insert &rarr; real IoTDB) against the
 * same {@code apache/iotdb:2.0.8-standalone} Testcontainer the functional ITs use, then reports
 * records/sec, error rate, and writer stats.
 *
 * <p><b>The &gt;10K writes/sec headline target is the FULL profile number on a dedicated host.</b>
 * A cold single-node Testcontainer on a laptop/CI runner will not reach it, so this smoke test only
 * asserts a deliberately conservative throughput floor plus strict correctness (zero failures, zero
 * rejects, flushed == rows written, sample rows persisted). Its job is to guard against gross
 * throughput regressions and prove real end-to-end ingestion, not to certify the headline number.
 * The full multi-backend comparison (Cassandra / PostgreSQL / TimescaleDB) is later-scope and is
 * not built here.
 *
 * @since 2.0.4-SNAPSHOT
 */
@Tag("benchmark")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class IoTDBTableIngestionBenchmarkIT {
  private static final Logger LOG = LoggerFactory.getLogger(IoTDBTableIngestionBenchmarkIT.class);

  // Smoke sizing: concurrency mirrors the TC-1 design (50 concurrent threads) but the total row
  // count is kept modest so the run finishes in well under the integration-test budget on a cold
  // container. ROWS_PER_THREAD is chosen so SAVER_THREADS * ROWS_PER_THREAD stays comfortably
  // below the production save queue capacity (50_000), which keeps the run free of back-pressure
  // rejects without changing the real defaults.
  private static final int SAVER_THREADS = 50;
  private static final int ROWS_PER_THREAD = 600;
  private static final int TOTAL_ROWS = SAVER_THREADS * ROWS_PER_THREAD; // 30_000

  // Conservative smoke floor. The >10K rows/sec design-doc target is the FULL-profile headline on a
  // dedicated host; a cold single-node Testcontainer cannot be held to it without flakiness, so we
  // only assert that the real save() path sustains at least this floor end-to-end. Raise this only
  // alongside a measured full-profile report (docs/benchmarks/report.md), never to chase the
  // headline on CI.
  private static final double SMOKE_THROUGHPUT_FLOOR_ROWS_PER_SEC = 1_000.0D;

  private static final int FUTURE_TIMEOUT_SECONDS = 60;
  // One global ceiling for awaiting the WHOLE set of save futures. A systemic writer stall must
  // fail
  // the smoke benchmark within this bound instead of applying a per-future timeout to each of
  // TOTAL_ROWS futures in turn (which would let a hang run for hours before CI kills it).
  private static final int AWAIT_ALL_TIMEOUT_SECONDS = 120;
  private static final int VERIFY_SAMPLE_KEYS = 5;
  private static final Duration IOTDB_STARTUP_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IOTDB_READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration IOTDB_READY_POLL_INTERVAL = Duration.ofMillis(500);

  @Container
  static final GenericContainer<?> IOTDB =
      new GenericContainer<>(DockerImageName.parse("apache/iotdb:2.0.8-standalone"))
          .withExposedPorts(6667)
          // IoTDB binds its client RPC service to dn_rpc_address (default 127.0.0.1), so it would
          // only listen on the container loopback and reject the Testcontainers port-mapped session
          // handshake ("Fail to reconnect"). Bind to all interfaces so the mapped host port works.
          .withEnv("dn_rpc_address", "0.0.0.0")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(IOTDB_STARTUP_TIMEOUT));

  @Test
  void tc1_ingestionThroughput_smokeProfile() throws Exception {
    BenchmarkScope scope = scope();
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = benchmarkConfig();
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);

      ExecutorService savers = Executors.newFixedThreadPool(SAVER_THREADS, saverThreadFactory());
      List<ListenableFuture<Integer>> futures = new ArrayList<>(TOTAL_ROWS);
      AtomicInteger failedSubmits = new AtomicInteger();
      CountDownLatch ready = new CountDownLatch(SAVER_THREADS);
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(SAVER_THREADS);

      try {
        // Each thread owns a disjoint entity + timestamp band so every (entity, key, ts) tuple is
        // unique across the whole run; nothing is deduplicated away and flushed must equal
        // TOTAL_ROWS.
        for (int threadIndex = 0; threadIndex < SAVER_THREADS; threadIndex++) {
          int t = threadIndex;
          savers.execute(
              () -> {
                EntityId entity = entityForThread(t);
                List<ListenableFuture<Integer>> local = new ArrayList<>(ROWS_PER_THREAD);
                ready.countDown();
                try {
                  start.await();
                  for (int r = 0; r < ROWS_PER_THREAD; r++) {
                    long ts = ((long) t * ROWS_PER_THREAD) + r + 1L;
                    BasicTsKvEntry entry =
                        new BasicTsKvEntry(ts, new LongDataEntry("metric", (long) r));
                    local.add(dao.save(scope.tenantId(), entity, entry, 0));
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                  failedSubmits.incrementAndGet();
                  LOG.warn("TC-1 saver thread {} failed to submit", t, e);
                } finally {
                  synchronized (futures) {
                    futures.addAll(local);
                  }
                  done.countDown();
                }
              });
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "saver threads did not become ready in time");
        long startNanos = System.nanoTime();
        start.countDown();
        assertTrue(
            done.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "saver threads did not finish submitting in time");

        assertEquals(0, failedSubmits.get(), "save() submission must not throw");
        assertEquals(TOTAL_ROWS, futures.size(), "every row must produce a save future");

        long failedFutures = awaitAll(futures);
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0D;
        double recordsPerSec = TOTAL_ROWS / elapsedSeconds;
        double errorRate = (double) failedFutures / TOTAL_ROWS;

        IoTDBTableTimeseriesWriterStats stats = dao.stats();
        long persistedSample = countPersistedSample(pool, scope);

        String report =
            String.format(
                "TC-1 ingestion smoke profile: rows=%d threads=%d batchSize=%d queueCapacity=%d "
                    + "elapsed=%.2fs recordsPerSec=%.0f errorRate=%.4f | stats[enqueued=%d "
                    + "flushed=%d flushFailures=%d retries=%d rejectsFull=%d rejectsShutdown=%d "
                    + "queueDepth=%d] | persistedSampleRows=%d (across %d sampled threads) "
                    + "| floor=%.0frows/sec (NOTE: >10K rows/sec is the FULL-profile headline on a "
                    + "dedicated host; this smoke run only guards regressions)",
                TOTAL_ROWS,
                SAVER_THREADS,
                config.getTs().getSave().getBatchSize(),
                config.getTs().getSave().getQueueCapacity(),
                elapsedSeconds,
                recordsPerSec,
                errorRate,
                stats.enqueued(),
                stats.flushed(),
                stats.flushFailures(),
                stats.retries(),
                stats.rejectsFull(),
                stats.rejectsShutdown(),
                stats.queueDepth(),
                persistedSample,
                VERIFY_SAMPLE_KEYS,
                SMOKE_THROUGHPUT_FLOOR_ROWS_PER_SEC);
        LOG.info(report);
        // Also emit to stdout so the measured records/sec is captured in the surefire/failsafe
        // console output even when no SLF4J binding is on the test classpath (NOP logger).
        System.out.println(report);

        // Correctness: the real save path must complete every row with no failures or rejects.
        assertEquals(0L, failedFutures, "TC-1 smoke profile must complete with zero failed saves");
        assertEquals(0.0D, errorRate, "TC-1 smoke profile error rate must be zero");
        assertEquals(0L, stats.flushFailures(), "writer flushFailures must be zero");
        assertEquals(
            0L, stats.rejectsFull(), "writer rejectsFull must be zero (queue not saturated)");
        assertEquals(0L, stats.rejectsShutdown(), "writer rejectsShutdown must be zero");
        assertEquals(
            TOTAL_ROWS,
            stats.flushed(),
            "every distinct row must be flushed (nothing deduplicated)");

        // Proof of real ingestion: a sample of rows must be readable back from IoTDB.
        assertEquals(
            VERIFY_SAMPLE_KEYS,
            persistedSample,
            "sampled rows must be persisted and readable from IoTDB");

        // Conservative regression floor, not the >10K rows/sec full-profile headline.
        assertTrue(
            recordsPerSec >= SMOKE_THROUGHPUT_FLOOR_ROWS_PER_SEC,
            () ->
                "TC-1 smoke throughput "
                    + String.format("%.0f", recordsPerSec)
                    + " rows/sec fell below the conservative smoke floor "
                    + String.format("%.0f", SMOKE_THROUGHPUT_FLOOR_ROWS_PER_SEC)
                    + " rows/sec (full-profile target is >10K on a dedicated host)");
      } finally {
        savers.shutdownNow();
        try {
          // Best-effort: on an early assertion failure, let interrupted saver threads unwind before
          // we tear down the DAO/writer they may still be calling into.
          savers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        dao.destroy();
        writer.destroy();
      }
    }
  }

  private long awaitAll(List<ListenableFuture<Integer>> futures) throws InterruptedException {
    // Single shared deadline for the whole set: once it passes, each remaining future is polled
    // with
    // a zero (non-blocking) budget, so a stall is detected fast and the total wait is bounded by
    // AWAIT_ALL_TIMEOUT_SECONDS regardless of how many futures are outstanding.
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_ALL_TIMEOUT_SECONDS);
    long failed = 0L;
    Throwable firstFailure = null;
    for (ListenableFuture<Integer> future : futures) {
      long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
      try {
        future.get(remainingNanos, TimeUnit.NANOSECONDS);
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
        failed++;
        if (firstFailure == null) {
          firstFailure = e;
        }
      }
    }
    if (failed > 0L) {
      LOG.warn(
          "TC-1: {} of {} save futures did not complete within {}s (first failure shown)",
          failed,
          futures.size(),
          AWAIT_ALL_TIMEOUT_SECONDS,
          firstFailure);
    }
    return failed;
  }

  /**
   * Reads back the first row written by the first {@link #VERIFY_SAMPLE_KEYS} threads to prove the
   * benchmark persisted real rows rather than merely completing futures.
   */
  private long countPersistedSample(ITableSessionPool pool, BenchmarkScope scope) throws Exception {
    long found = 0L;
    for (int t = 0; t < VERIFY_SAMPLE_KEYS; t++) {
      EntityId entity = entityForThread(t);
      long ts = ((long) t * ROWS_PER_THREAD) + 1L;
      String sql =
          "SELECT long_v FROM telemetry WHERE tenant_id='"
              + scope.tenantId().getId()
              + "' AND entity_type='DEVICE' AND entity_id='"
              + entity.getId()
              + "' AND key='metric' AND time="
              + ts;
      try (ITableSession session = pool.getSession();
          SessionDataSet dataSet = session.executeQueryStatement(sql)) {
        SessionDataSet.DataIterator row = dataSet.iterator();
        if (row.next() && !row.isNull("long_v")) {
          found++;
        }
      }
    }
    return found;
  }

  private EntityId entityForThread(int threadIndex) {
    // Deterministic per-thread device UUID so each thread targets a distinct entity.
    UUID id = new UUID(0xBE0000000000L, 0x1000L + threadIndex);
    return new BenchmarkEntityId(id);
  }

  private IoTDBTableConfig benchmarkConfig() {
    // Production save-path defaults: batchSize=500, queueCapacity=50000,
    // maxLingerMs=20, flushThreads=1, sessionPoolSize=8. Only the retry backoff is shortened so a
    // transient cold-start blip does not stretch the measured window; the throughput-relevant
    // knobs are left at their real defaults so the smoke run exercises the real configuration.
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getSave().setRetryInitialBackoffMs(1L);
    config.getTs().getSave().setRetryMaxBackoffMs(1L);
    config.getTs().getRead().setThreads(1);
    return config;
  }

  private ITableSessionPool newPool(String database) {
    TableSessionPoolBuilder builder =
        new TableSessionPoolBuilder()
            .nodeUrls(List.of("127.0.0.1:" + IOTDB.getMappedPort(6667)))
            .user("root")
            .password("root")
            .maxSize(8);
    if (database != null) {
      builder.database(database);
    }
    return builder.build();
  }

  private void bootstrapSchema(String database) throws Exception {
    awaitIoTDBReady(database);

    String schema;
    try (InputStream stream =
        IoTDBTableIngestionBenchmarkIT.class
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
    try (ITableSessionPool bootstrapPool = newPool(null);
        ITableSession session = bootstrapPool.getSession()) {
      for (String statement : schema.split(";")) {
        String trimmed = statement.trim();
        if (!trimmed.isEmpty()) {
          session.executeNonQueryStatement(trimmed);
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

  private BenchmarkScope scope() {
    return new BenchmarkScope(
        uniqueDatabase(), new TenantId(UUID.fromString("55555555-5555-5555-5555-555555555501")));
  }

  private String uniqueDatabase() {
    // IoTDB caps database names at 64 chars; keep the prefix short and append a trimmed UUID.
    String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "tb_bench_tc1_" + shortUuid;
  }

  private static java.util.concurrent.ThreadFactory saverThreadFactory() {
    AtomicLong sequence = new AtomicLong();
    return runnable -> {
      Thread thread = new Thread(runnable, "tc1-benchmark-saver-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private record BenchmarkScope(String database, TenantId tenantId) {}

  private record BenchmarkEntityId(UUID id) implements EntityId {
    @Override
    public UUID getId() {
      return id;
    }

    @Override
    public EntityType getEntityType() {
      return EntityType.DEVICE;
    }
  }
}
