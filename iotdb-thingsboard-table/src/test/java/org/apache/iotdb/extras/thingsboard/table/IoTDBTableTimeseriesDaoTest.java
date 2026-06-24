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

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.rpc.TSStatusCode;

import com.google.common.util.concurrent.ListenableFuture;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseDeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the IoTDB Table Mode timeseries DAO: the WRITE path (multi-row Tablet mapping,
 * batch flushing, connection retry, back-pressure rejection and graceful-shutdown drain) plus the
 * RAW (non-aggregated) READ path, the DELETE path and the bounded read thread-pool. The
 * time-bucketed aggregation read path is not implemented and is not exercised here.
 */
class IoTDBTableTimeseriesDaoTest {
  private static final TenantId TENANT_ID =
      new TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final EntityId ENTITY_ID =
      new TestEntityId(UUID.fromString("22222222-2222-2222-2222-222222222222"), EntityType.DEVICE);

  private IoTDBTableTimeseriesWriter writer;
  private final List<IoTDBTableTimeseriesDao> daos = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (IoTDBTableTimeseriesDao dao : daos) {
      dao.destroy();
    }
    daos.clear();
    if (writer != null) {
      writer.destroy();
    }
  }

  @Test
  void save_mapsAllDataTypesIntoSparseTablet() throws Exception {
    TestContext context = newContext(config(5, 1000L, 100), true);

    List<ListenableFuture<Integer>> futures =
        List.of(
            context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "bool", DataType.BOOLEAN, true), 0),
            context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "long", DataType.LONG, 42L), 0),
            context.dao().save(TENANT_ID, ENTITY_ID, entry(3L, "double", DataType.DOUBLE, 3.5D), 0),
            context
                .dao()
                .save(TENANT_ID, ENTITY_ID, entry(4L, "string", DataType.STRING, "abc"), 0),
            context
                .dao()
                .save(TENANT_ID, ENTITY_ID, entry(5L, "json", DataType.JSON, "{\"v\":1}"), 0));

    for (ListenableFuture<Integer> future : futures) {
      assertEquals(1, future.get(3, TimeUnit.SECONDS));
    }

    Tablet tablet = insertedTablet(context.session(), 1);
    assertEquals("telemetry", tablet.getTableName());
    assertEquals(5, tablet.getRowSize());
    assertEquals(IoTDBTableTimeseriesWriter.COLUMN_NAMES, schemaNames(tablet));
    assertEquals(IoTDBTableTimeseriesWriter.DATA_TYPES, schemaTypes(tablet));
    assertEquals(
        List.of(
            ColumnCategory.TAG,
            ColumnCategory.TAG,
            ColumnCategory.TAG,
            ColumnCategory.TAG,
            ColumnCategory.FIELD,
            ColumnCategory.FIELD,
            ColumnCategory.FIELD,
            ColumnCategory.FIELD,
            ColumnCategory.FIELD),
        tablet.getColumnTypes());

    assertRow(tablet, 0, 1L, "bool", 4);
    assertRow(tablet, 1, 2L, "long", 5);
    assertRow(tablet, 2, 3L, "double", 6);
    assertRow(tablet, 3, 4L, "string", 7);
    assertRow(tablet, 4, 5L, "json", 8);
  }

  @Test
  void save_returnsDataPointDaysWithEffectiveTtlAndEntryAmplification() throws Exception {
    IoTDBTableConfig config = config(1, 1000L, 100);
    config.setDefaultTtlMs(TimeUnit.DAYS.toMillis(2));
    TestContext context = newContext(config, true);
    String largeString = "s".repeat(513);
    String largeJson = "{\"v\":\"" + "j".repeat(1025) + "\"}";

    ListenableFuture<Integer> defaultTtlFuture =
        context
            .dao()
            .save(
                TENANT_ID,
                ENTITY_ID,
                entry(1L, "largeString", DataType.STRING, largeString, tbDataPoints(largeString)),
                0);
    ListenableFuture<Integer> perCallTtlFuture =
        context
            .dao()
            .save(
                TENANT_ID,
                ENTITY_ID,
                entry(2L, "largeJson", DataType.JSON, largeJson, tbDataPoints(largeJson)),
                TimeUnit.DAYS.toSeconds(1));

    assertEquals(2 * tbDataPoints(largeString), defaultTtlFuture.get(3, TimeUnit.SECONDS));
    assertEquals(tbDataPoints(largeJson), perCallTtlFuture.get(3, TimeUnit.SECONDS));
  }

  @Test
  void save_returnsOneDayEquivalentWhenEffectiveTtlIsLessThanOneDay() throws Exception {
    TestContext context = newContext(config(1, 1000L, 100), true);

    ListenableFuture<Integer> future =
        context
            .dao()
            .save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.DOUBLE, 21.5D), 3600L);

    assertEquals(1, future.get(3, TimeUnit.SECONDS));
  }

  @Test
  void savePartition_returnsImmediateZeroAndDoesNotWrite() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);

    assertEquals(
        0,
        context
            .dao()
            .savePartition(TENANT_ID, ENTITY_ID, 1L, "temperature")
            .get(3, TimeUnit.SECONDS));

    assertEquals(0, context.dao().stats().enqueued());
    verify(context.session(), never()).insert(any(Tablet.class));
  }

  @Test
  void save_rejectsBlankTelemetryKeyBeforeEnqueue() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);

    assertFutureFailsWith(
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, " ", DataType.LONG, 1L), 0),
        IllegalArgumentException.class);

    assertEquals(0, context.dao().stats().enqueued());
    verify(context.session(), never()).insert(any(Tablet.class));
  }

  @Test
  void cleanup_isNoOp() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);

    context.dao().cleanup(TimeUnit.DAYS.toMillis(7));

    verify(context.pool(), never()).getSession();
  }

  @Test
  void save_flushesOneTabletWhenBatchThresholdIsReached() throws Exception {
    TestContext context = newContext(config(500, 10000L, 1000), true);
    List<ListenableFuture<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      futures.add(
          context
              .dao()
              .save(
                  TENANT_ID, ENTITY_ID, entry(i, "temperature-" + i, DataType.LONG, (long) i), 0));
    }

    for (ListenableFuture<Integer> future : futures) {
      assertEquals(1, future.get(5, TimeUnit.SECONDS));
    }

    Tablet tablet = insertedTablet(context.session(), 1);
    assertEquals(500, tablet.getRowSize());
    assertEquals(500, context.dao().stats().flushed());
  }

  @Test
  void save_flushesAfterMaxLingerWhenBatchIsNotFull() throws Exception {
    TestContext context = newContext(config(500, 20L, 1000), true);

    ListenableFuture<Integer> future =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);

    assertEquals(1, future.get(3, TimeUnit.SECONDS));
    Tablet tablet = insertedTablet(context.session(), 1);
    assertEquals(1, tablet.getRowSize());
  }

  @Test
  void save_rejectsImmediatelyWhenQueueIsFull() throws Exception {
    TestContext context = newContext(config(500, 10000L, 1), false);

    ListenableFuture<Integer> accepted =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0);
    ListenableFuture<Integer> rejected =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0);

    assertFalse(accepted.isDone());
    assertFutureFailsWith(rejected, IoTDBTableSaveQueueFullException.class);
    assertEquals(1, context.dao().stats().rejectsFull());
    verify(context.session(), never()).insert(any(Tablet.class));
  }

  @Test
  void rejectWarningLimiterAllowsFirstRejectThenSuppressesUntilWindowExpires() {
    AtomicLong clock = new AtomicLong(0L);
    writer = newWriterWithClock(config(1, 1000L, 1), clock);

    assertTrue(writer.shouldLogRejectedSaveWarning());
    assertFalse(writer.shouldLogRejectedSaveWarning());

    clock.set(TimeUnit.SECONDS.toNanos(10) - 1L);
    assertFalse(writer.shouldLogRejectedSaveWarning());

    clock.set(TimeUnit.SECONDS.toNanos(10) + 1L);
    assertTrue(writer.shouldLogRejectedSaveWarning());
  }

  @Test
  void queueFullAndShutdownRejectsShareWarningLimiter() throws Exception {
    AtomicLong clock = new AtomicLong(0L);
    writer = newWriterWithClock(config(1, 1000L, 1), clock);

    IoTDBTablePendingSave accepted = pendingSave(1L, "accepted");
    IoTDBTablePendingSave queueFull = pendingSave(2L, "queue-full");
    writer.enqueue(accepted);
    writer.enqueue(queueFull);

    assertFalse(accepted.future().isDone());
    assertFutureFailsWith(queueFull.future(), IoTDBTableSaveQueueFullException.class);
    assertEquals(1, writer.stats().rejectsFull());
    assertFalse(writer.shouldLogRejectedSaveWarning());

    clock.set(TimeUnit.SECONDS.toNanos(10) + 1L);
    writer.destroy();
    assertFutureFailsWith(accepted.future(), IoTDBTableDaoShuttingDownException.class);

    IoTDBTablePendingSave shutdown = pendingSave(3L, "shutdown");
    writer.enqueue(shutdown);

    assertFutureFailsWith(shutdown.future(), IoTDBTableDaoShuttingDownException.class);
    assertEquals(1, writer.stats().rejectsShutdown());
    assertFalse(writer.shouldLogRejectedSaveWarning());
  }

  @Test
  void initialBackoffMsCapsInitialBackoffOnlyWhenMaxIsPositive() {
    assertEquals(10L, IoTDBTableTimeseriesWriter.initialBackoffMs(50L, 10L));
    assertEquals(10L, IoTDBTableTimeseriesWriter.initialBackoffMs(10L, 50L));
    assertEquals(50L, IoTDBTableTimeseriesWriter.initialBackoffMs(50L, 0L));
    assertEquals(50L, IoTDBTableTimeseriesWriter.initialBackoffMs(50L, -1L));
  }

  @Test
  void save_retriesConnectionExceptionThenCompletesWholeBatch() throws Exception {
    TestContext context = newContext(config(2, 1000L, 100), true);
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (attempts.getAndIncrement() == 0) {
                throw new IoTDBConnectionException("temporary connection failure");
              }
              return null;
            })
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> first =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0);
    ListenableFuture<Integer> second =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0);

    assertEquals(1, first.get(3, TimeUnit.SECONDS));
    assertEquals(1, second.get(3, TimeUnit.SECONDS));
    verify(context.session(), timeout(3000).times(2)).insert(any(Tablet.class));
    assertEquals(1, context.dao().stats().retries());
    assertEquals(2, context.dao().stats().flushed());
  }

  @Test
  void saveTreatsCloseAfterSuccessfulInsertAsSuccessWithoutReplay() throws Exception {
    TestContext context = newContext(config(1, 1000L, 100), true);
    doThrow(new IoTDBConnectionException("close failed")).when(context.session()).close();

    ListenableFuture<Integer> future =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 1L), 0);

    assertEquals(1, future.get(3, TimeUnit.SECONDS));
    verify(context.session(), timeout(3000).times(1)).insert(any(Tablet.class));
    assertEquals(1, context.dao().stats().flushed());
    assertEquals(0, context.dao().stats().retries());
    assertEquals(0, context.dao().stats().flushFailures());
  }

  @Test
  void save_doesNotRetryStatementExecutionExceptionAndFailsWholeBatch() throws Exception {
    TestContext context = newContext(config(2, 1000L, 100), true);
    doThrow(new StatementExecutionException("bad statement"))
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> first =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0);
    ListenableFuture<Integer> second =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0);

    assertFutureFailsWith(first, StatementExecutionException.class);
    assertFutureFailsWith(second, StatementExecutionException.class);
    verify(context.session(), timeout(3000).times(1)).insert(any(Tablet.class));
    assertEquals(0, context.dao().stats().retries());
    assertEquals(1, context.dao().stats().flushFailures());
  }

  @Test
  void save_retriesTransientStatementExecutionExceptionThenFailsAfterExhaustingAttempts()
      throws Exception {
    // A transient server-side status code (WRITE_PROCESS_REJECT) is retried up to
    // retryMaxAttempts. Every attempt keeps failing, so the batch ultimately surfaces a failure
    // after the whole retry budget is spent: insert is called retryMaxAttempts times.
    IoTDBTableConfig config = config(2, 1000L, 100);
    config.getTs().getSave().setRetryMaxAttempts(3);
    TestContext context = newContext(config, true);
    doThrow(statementExecutionException(TSStatusCode.WRITE_PROCESS_REJECT))
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> first =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0);
    ListenableFuture<Integer> second =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0);

    assertFutureFailsWith(first, StatementExecutionException.class);
    assertFutureFailsWith(second, StatementExecutionException.class);
    verify(context.session(), timeout(3000).times(3)).insert(any(Tablet.class));
    assertEquals(2, context.dao().stats().retries());
    assertEquals(1, context.dao().stats().flushFailures());
  }

  @Test
  void save_failsFastOnPermanentStatementExecutionExceptionWithoutRetrying() throws Exception {
    // A non-transient status code (EXECUTE_STATEMENT_ERROR is a semantic/permanent failure, not in
    // the transient set) must NOT be retried even though retryMaxAttempts > 1: insert is called
    // exactly once and the batch fails fast.
    IoTDBTableConfig config = config(2, 1000L, 100);
    config.getTs().getSave().setRetryMaxAttempts(3);
    TestContext context = newContext(config, true);
    doThrow(statementExecutionException(TSStatusCode.EXECUTE_STATEMENT_ERROR))
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> first =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0);
    ListenableFuture<Integer> second =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0);

    assertFutureFailsWith(first, StatementExecutionException.class);
    assertFutureFailsWith(second, StatementExecutionException.class);
    verify(context.session(), timeout(3000).times(1)).insert(any(Tablet.class));
    assertEquals(0, context.dao().stats().retries());
    assertEquals(1, context.dao().stats().flushFailures());
  }

  @Test
  void destroyDrainsPendingWritesAndRejectsNewSaves() throws Exception {
    TestContext context = newContext(config(10, 10000L, 100), true);
    ListenableFuture<Integer> pending =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);

    writer.destroy();

    assertEquals(1, pending.get(3, TimeUnit.SECONDS));
    verify(context.session(), timeout(3000).times(1)).insert(any(Tablet.class));

    ListenableFuture<Integer> rejected =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "temperature", DataType.LONG, 43L), 0);
    assertFutureFailsWith(rejected, IoTDBTableDaoShuttingDownException.class);
    assertEquals(1, context.dao().stats().rejectsShutdown());
  }

  @Test
  void destroyDrainsInFlightRetryAfterTransientErrorInsteadOfFailingTheBatch() throws Exception {
    // A transient IoTDBConnectionException puts the worker into retry backoff; a concurrent
    // destroy()
    // must let the bounded drain window finish the retry rather than interrupt the backoff sleep
    // and
    // fail an already-accepted batch. The drain timeout is long and the backoff is long enough that
    // destroy() runs while the worker is sleeping between attempts, then the retry completes.
    IoTDBTableConfig config = config(1, 1000L, 100);
    config.getTs().getSave().setRetryMaxAttempts(3);
    config.getTs().getSave().setRetryInitialBackoffMs(300L);
    config.getTs().getSave().setRetryMaxBackoffMs(300L);
    config.getTs().getSave().setShutdownDrainTimeoutMs(5000L);
    TestContext context = newContext(config, true);

    CountDownLatch firstInsertFailed = new CountDownLatch(1);
    AtomicInteger inserts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (inserts.incrementAndGet() == 1) {
                firstInsertFailed.countDown();
                throw new IoTDBConnectionException("transient blip");
              }
              return null; // the retry succeeds
            })
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> save =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);
    assertTrue(
        firstInsertFailed.await(3, TimeUnit.SECONDS),
        "worker should hit the transient error and enter retry backoff");

    writer.destroy(); // must NOT interrupt the in-flight retry backoff

    assertEquals(
        1,
        save.get(5, TimeUnit.SECONDS),
        "the accepted batch must drain (retry completes) rather than fail on shutdown");
    verify(context.session(), timeout(5000).times(2)).insert(any(Tablet.class));
    assertEquals(1, context.dao().stats().retries());
    assertEquals(0, context.dao().stats().flushFailures());
  }

  @Test
  void destroyFlushesAPartialBatchPromptlyInsteadOfWaitingOutMaxLinger() throws Exception {
    // A worker that has dequeued a PARTIAL batch and is in the linger poll must observe shutdown
    // within one poll slice and flush promptly rather than wait out the full maxLingerMs.
    // batchSize=2
    // with a single queued item keeps the worker lingering for a second item; a 30s linger makes an
    // un-sliced poll fail the fast assertion below.
    IoTDBTableConfig config = config(2, 30_000L, 100); // batchSize=2, maxLingerMs=30s
    config.getTs().getSave().setShutdownDrainTimeoutMs(30_000L);
    TestContext context = newContext(config, true);

    ListenableFuture<Integer> save =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);

    // destroy() on a separate thread so the test thread asserts the prompt flush regardless of
    // join.
    Thread closer = new Thread(writer::destroy, "closer");
    closer.start();

    // With the slice fix the partial batch flushes within ~one slice of shutdown; without it the
    // worker would wait out the 30s linger and this get() would time out.
    assertEquals(
        1,
        save.get(3, TimeUnit.SECONDS),
        "partial batch must flush promptly on shutdown, not wait out maxLingerMs");
    verify(context.session(), timeout(3000).times(1)).insert(any(Tablet.class));
    closer.join(5_000L);
  }

  @Test
  void destroyForceStopsDequeuedLingeringBatchAndSettlesFuture() throws Exception {
    IoTDBTableConfig config = config(2, 60_000L, 100);
    config.getTs().getSave().setShutdownDrainTimeoutMs(1L);
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    DequeuedBatchLatchQueue queue =
        new DequeuedBatchLatchQueue(config.getTs().getSave().getQueueCapacity());
    writer = new IoTDBTableTimeseriesWriter(pool, config, true, queue);
    IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
    daos.add(dao);

    ListenableFuture<Integer> save =
        dao.save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);
    assertTrue(
        queue.awaitLingerPollStarted(3, TimeUnit.SECONDS),
        "worker should dequeue the save and start waiting for the rest of the batch");

    writer.destroy();

    assertFutureFailsWith(save, IoTDBTableDaoShuttingDownException.class);
    Thread workerThread = writer.workerThread();
    workerThread.join(1_000L);
    assertFalse(workerThread.isAlive(), "forced-stop worker should terminate");
    verify(session, never()).insert(any(Tablet.class));
  }

  @Test
  void destroyForceStopsSavesFrozenBetweenDrainToAndBatchAppend() throws Exception {
    IoTDBTableConfig config = config(3, 60_000L, 100);
    config.getTs().getSave().setShutdownDrainTimeoutMs(1L);
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    BlockingDrainToQueue queue =
        new BlockingDrainToQueue(config.getTs().getSave().getQueueCapacity());
    writer = new IoTDBTableTimeseriesWriter(pool, config, false, queue);
    IoTDBTablePendingSave first = pendingSave(1L, "first");
    IoTDBTablePendingSave duplicateA = pendingSave(2L, "duplicate");
    IoTDBTablePendingSave duplicateB = pendingSave(2L, "duplicate");
    assertEquals(duplicateA.identity(), duplicateB.identity());
    assertFalse(duplicateA.future() == duplicateB.future());

    writer.enqueue(first);
    writer.enqueue(duplicateA);
    writer.enqueue(duplicateB);
    Thread workerThread = writer.workerThread();
    try {
      workerThread.start();
      assertTrue(
          queue.awaitDrainToBlocked(3, TimeUnit.SECONDS),
          "worker should block after drainTo removes saves and before batch append");

      writer.destroy();

      assertFutureFailsWith(first.future(), IoTDBTableDaoShuttingDownException.class);
      assertFutureFailsWith(duplicateA.future(), IoTDBTableDaoShuttingDownException.class);
      assertFutureFailsWith(duplicateB.future(), IoTDBTableDaoShuttingDownException.class);
    } finally {
      queue.releaseDrainTo();
    }
    workerThread.join(1_000L);
    assertFalse(workerThread.isAlive(), "forced-stop worker should terminate");
    verify(session, never()).insert(any(Tablet.class));
  }

  @Test
  void destroyForceStopsRetryBackoffPromptlyWithoutReplayingInsert() throws Exception {
    IoTDBTableConfig config = config(1, 1000L, 100);
    config.getTs().getSave().setRetryMaxAttempts(3);
    config.getTs().getSave().setRetryInitialBackoffMs(60_000L);
    config.getTs().getSave().setRetryMaxBackoffMs(60_000L);
    config.getTs().getSave().setShutdownDrainTimeoutMs(1L);
    TestContext context = newContext(config, true);

    CountDownLatch firstInsertFailed = new CountDownLatch(1);
    AtomicInteger inserts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (inserts.incrementAndGet() == 1) {
                firstInsertFailed.countDown();
                throw new IoTDBConnectionException("transient blip");
              }
              return null;
            })
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> save =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);
    assertTrue(
        firstInsertFailed.await(3, TimeUnit.SECONDS),
        "worker should enter retry handling after the first insert fails");

    long startedNanos = System.nanoTime();
    writer.destroy();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

    assertTrue(elapsedMs < 2000L, "destroy should not wait out the retry backoff");
    assertFutureFailsWith(save, IoTDBTableDaoShuttingDownException.class);
    Thread workerThread = writer.workerThread();
    workerThread.join(1_000L);
    assertFalse(workerThread.isAlive(), "forced-stop worker should terminate");
    verify(context.session(), times(1)).insert(any(Tablet.class));
  }

  @Test
  void destroyForceStopPreventsInsertAfterBlockedSessionAcquisitionReleases() throws Exception {
    IoTDBTableConfig config = config(1, 1000L, 100);
    config.getTs().getSave().setShutdownDrainTimeoutMs(1L);
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    CountDownLatch getSessionStarted = new CountDownLatch(1);
    CountDownLatch releaseGetSession = new CountDownLatch(1);
    when(pool.getSession())
        .thenAnswer(
            invocation -> {
              getSessionStarted.countDown();
              boolean released = false;
              while (!released) {
                try {
                  released = releaseGetSession.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  // Model session acquisition that does not react to forced-stop interruption.
                }
              }
              return session;
            });
    writer = new IoTDBTableTimeseriesWriter(pool, config, true);
    IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
    daos.add(dao);

    ListenableFuture<Integer> save =
        dao.save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 0);
    assertTrue(
        getSessionStarted.await(3, TimeUnit.SECONDS),
        "worker should block while acquiring a table session");

    writer.destroy();
    releaseGetSession.countDown();
    Thread workerThread = writer.workerThread();
    workerThread.join(1_000L);

    assertFalse(
        workerThread.isAlive(), "forced-stop worker should terminate after getSession returns");
    assertFutureFailsWith(save, IoTDBTableDaoShuttingDownException.class);
    verify(session, never()).insert(any(Tablet.class));
  }

  @Test
  void destroyCompletesEveryFutureUnderConcurrentSavesAndDestroy() throws Exception {
    int saverThreads = 32;
    IoTDBTableConfig config = config(saverThreads, 10000L, saverThreads);
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    PausingOfferQueue queue = new PausingOfferQueue(saverThreads, saverThreads);
    writer = new IoTDBTableTimeseriesWriter(pool, config, false, queue);
    IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
    daos.add(dao);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(saverThreads);
    List<Future<ListenableFuture<Integer>>> submitted = new ArrayList<>(saverThreads);
    try {
      for (int i = 0; i < saverThreads; i++) {
        final int index = i;
        submitted.add(
            executor.submit(
                () -> {
                  start.await();
                  return dao.save(
                      TENANT_ID,
                      ENTITY_ID,
                      entry(index, "race-" + index, DataType.LONG, (long) index),
                      0);
                }));
      }

      start.countDown();
      assertTrue(queue.awaitPausedOffers(3, TimeUnit.SECONDS));
      writer.destroy();
      queue.releaseOffers();

      for (Future<ListenableFuture<Integer>> submittedFuture : submitted) {
        ListenableFuture<Integer> saveFuture = submittedFuture.get(3, TimeUnit.SECONDS);
        assertTrue(saveFuture.isDone());
        assertFutureFailsWith(saveFuture, IoTDBTableDaoShuttingDownException.class);
      }

      IoTDBTableTimeseriesWriterStats stats = dao.stats();
      assertEquals(saverThreads, stats.enqueued());
      assertEquals(0, stats.flushed());
      assertEquals(0, stats.rejectsFull());
      assertEquals(saverThreads, stats.rejectsShutdown());
      assertEquals(0, stats.shutdownFailedPending());
      assertEquals(
          stats.enqueued(),
          stats.flushed()
              + stats.rejectsFull()
              + stats.rejectsShutdown()
              + stats.shutdownFailedPending());
      verify(session, never()).insert(any(Tablet.class));
    } finally {
      queue.releaseOffers();
      executor.shutdownNow();
    }
  }

  @Test
  void destroyTimeoutFailsActiveBatchWhileWorkerIsMidFlush() throws Exception {
    IoTDBTableConfig config = config(1, 1000L, 100);
    config.getTs().getSave().setShutdownDrainTimeoutMs(50L);
    TestContext context = newContext(config, true);
    CountDownLatch insertStarted = new CountDownLatch(1);
    CountDownLatch releaseInsert = new CountDownLatch(1);
    CountDownLatch insertReturned = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              insertStarted.countDown();
              boolean released = false;
              while (!released) {
                try {
                  released = releaseInsert.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  // Keep the mock insert in flight until the test releases it.
                }
              }
              insertReturned.countDown();
              return null;
            })
        .when(context.session())
        .insert(any(Tablet.class));

    ListenableFuture<Integer> active =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 1L), 0);
    assertTrue(insertStarted.await(3, TimeUnit.SECONDS));

    long startedNanos = System.nanoTime();
    writer.destroy();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

    assertTrue(elapsedMs < 2000L, "destroy should return within bounded shutdown waits");
    assertFutureFailsWith(active, IoTDBTableDaoShuttingDownException.class);
    assertTrue(context.dao().stats().shutdownFailedPending() > 0);
    releaseInsert.countDown();
    assertTrue(insertReturned.await(3, TimeUnit.SECONDS));
  }

  @Test
  void save_failsAllEntryFuturesWhenBatchInsertFails() throws Exception {
    TestContext context = newContext(config(3, 1000L, 100), true);
    doThrow(new StatementExecutionException("batch rejected"))
        .when(context.session())
        .insert(any(Tablet.class));

    List<ListenableFuture<Integer>> futures =
        List.of(
            context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0),
            context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0),
            context.dao().save(TENANT_ID, ENTITY_ID, entry(3L, "third", DataType.LONG, 3L), 0));

    for (ListenableFuture<Integer> future : futures) {
      assertFutureFailsWith(future, StatementExecutionException.class);
    }
    assertEquals(1, context.dao().stats().flushFailures());
  }

  @Test
  void save_failsBatchFuturesAndKeepsWorkerAliveWhenInsertThrowsError() throws Exception {
    TestContext context = newContext(config(2, 1000L, 100), true);
    AtomicInteger insertAttempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (insertAttempts.getAndIncrement() == 0) {
                throw new NoSuchMethodError("simulated");
              }
              return null;
            })
        .when(context.session())
        .insert(any(Tablet.class));

    List<ListenableFuture<Integer>> failedBatch =
        List.of(
            context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "first", DataType.LONG, 1L), 0),
            context.dao().save(TENANT_ID, ENTITY_ID, entry(2L, "second", DataType.LONG, 2L), 0));

    for (ListenableFuture<Integer> future : failedBatch) {
      assertFutureFailsWith(future, NoSuchMethodError.class);
    }
    assertEquals(1, context.dao().stats().flushFailures());

    List<ListenableFuture<Integer>> recoveredBatch =
        List.of(
            context.dao().save(TENANT_ID, ENTITY_ID, entry(3L, "third", DataType.LONG, 3L), 0),
            context.dao().save(TENANT_ID, ENTITY_ID, entry(4L, "fourth", DataType.LONG, 4L), 0));

    for (ListenableFuture<Integer> future : recoveredBatch) {
      assertEquals(1, future.get(3, TimeUnit.SECONDS));
    }
    verify(context.session(), timeout(3000).times(2)).insert(any(Tablet.class));
    assertEquals(1, context.dao().stats().flushFailures());
    assertEquals(2, context.dao().stats().flushed());
  }

  @Test
  void save_doesNotIssueAlterTableForPerCallTtl() throws Exception {
    TestContext context = newContext(config(1, 1000L, 100), true);

    ListenableFuture<Integer> future =
        context
            .dao()
            .save(TENANT_ID, ENTITY_ID, entry(1L, "temperature", DataType.LONG, 42L), 86400L);

    assertEquals(1, future.get(3, TimeUnit.SECONDS));
    verify(context.session(), never()).executeNonQueryStatement(any(String.class));
  }

  @Test
  void save_resolvesInBatchSameTimestampTypeChangeWithLastWriterWins() throws Exception {
    TestContext context = newContext(config(2, 1000L, 100), true);

    ListenableFuture<Integer> first =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "status", DataType.LONG, 1L), 0);
    ListenableFuture<Integer> second =
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "status", DataType.STRING, "ok"), 0);

    assertEquals(1, first.get(3, TimeUnit.SECONDS));
    assertEquals(1, second.get(3, TimeUnit.SECONDS));

    Tablet tablet = insertedTablet(context.session(), 1);
    assertEquals(1, tablet.getRowSize());
    assertRow(tablet, 0, 1L, "status", 7);
    assertEquals(1, context.dao().stats().flushed());
  }

  @Test
  void findAllAsync_rawBuildsHalfOpenSql() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("temperature", 100L, 200L, 17, "asc");
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    assertEquals(query.getId(), result.getQueryId());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT time, bool_v, long_v, double_v, str_v, json_v FROM telemetry "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature' AND time >= 100 AND time < 200 "
            + "ORDER BY time ASC LIMIT 17",
        sql.getValue());
  }

  @Test
  void findAllAsync_mapsFiveTypesToBasicTsKvEntry() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet allTypesDataSet =
        dataSet(
            row(10L, "bool_v", true),
            row(11L, "long_v", 42L),
            row(12L, "double_v", 3.5D),
            row(13L, "str_v", "abc"),
            row(14L, "json_v", "{\"v\":1}"));
    when(context.session().executeQueryStatement(anyString())).thenReturn(allTypesDataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("sensor", 0L, 20L, 10, "DESC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(5, data.size());
    assertMappedEntry(data.get(0), 10L, "sensor", DataType.BOOLEAN, true);
    assertMappedEntry(data.get(1), 11L, "sensor", DataType.LONG, 42L);
    assertMappedEntry(data.get(2), 12L, "sensor", DataType.DOUBLE, 3.5D);
    assertMappedEntry(data.get(3), 13L, "sensor", DataType.STRING, "abc");
    assertMappedEntry(data.get(4), 14L, "sensor", DataType.JSON, "{\"v\":1}");
  }

  @Test
  void findAllAsync_preservesOneResultPerQueryAndQueryId() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet firstDataSet = dataSet();
    SessionDataSet secondDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(firstDataSet, secondDataSet);

    ReadTsKvQuery first = new BaseReadTsKvQuery("first", 10L, 20L, 1, "DESC");
    ReadTsKvQuery second = new BaseReadTsKvQuery("second", 20L, 30L, 1, "DESC");
    List<ReadTsKvQueryResult> results =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(first, second))
            .get(3, TimeUnit.SECONDS);

    assertEquals(2, results.size());
    assertEquals(first.getId(), results.get(0).getQueryId());
    assertEquals(second.getId(), results.get(1).getQueryId());
  }

  @Test
  void findAllAsync_lastEntryTsIsMaxReturnedTs() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet dataSet = dataSet(row(30L, "long_v", 3L), row(10L, "long_v", 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("counter", 0L, 40L, 10, "DESC");
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    assertEquals(30L, result.getLastEntryTs());
  }

  @Test
  void findAllAsync_emptyResult() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    ReadTsKvQuery noRows = new BaseReadTsKvQuery("empty", 123L, 456L, 10, "DESC");
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(noRows))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    assertEquals(List.of(), result.getData());
    assertEquals(123L, result.getLastEntryTs());

    ReadTsKvQuery zeroLimit = new BaseReadTsKvQuery("empty", 123L, 456L, 0, "DESC");
    ReadTsKvQueryResult zeroLimitResult =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(zeroLimit))
            .get(3, TimeUnit.SECONDS)
            .get(0);
    assertEquals(List.of(), zeroLimitResult.getData());
    assertEquals(123L, zeroLimitResult.getLastEntryTs());
    verify(context.session(), times(1)).executeQueryStatement(anyString());
  }

  @Test
  void findAllAsync_escapesKeyAndRejectsBadOrder() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    ReadTsKvQuery escaped = new BaseReadTsKvQuery("a'b", 1L, 2L, 1, "desc");
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(escaped)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertTrue(sql.getValue().contains("key='a''b'"));

    ReadTsKvQuery badOrder = new BaseReadTsKvQuery("key", 1L, 2L, 1, "sideways");
    assertFutureFailsWith(
        context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(badOrder)),
        IllegalArgumentException.class);
    verify(context.session(), times(1)).executeQueryStatement(anyString());
  }

  @Test
  void readDeleteAndSaveRejectBlankTelemetryKeysBeforeSqlOrEnqueue() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);

    assertFutureFailsWith(
        context
            .dao()
            .findAllAsync(
                TENANT_ID, ENTITY_ID, List.of(new BaseReadTsKvQuery("  ", 1L, 2L, 1, "DESC"))),
        IllegalArgumentException.class);
    assertFutureFailsWith(
        context.dao().remove(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("\t", 1L, 2L)),
        IllegalArgumentException.class);
    assertFutureFailsWith(
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, " ", DataType.LONG, 1L), 0),
        IllegalArgumentException.class);

    assertEquals(0, context.dao().stats().enqueued());
    verify(context.session(), never()).executeQueryStatement(anyString());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
    verify(context.session(), never()).insert(any(Tablet.class));
  }

  @Test
  void remove_buildsHalfOpenDeleteSql() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);

    assertNull(
        context
            .dao()
            .remove(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 100L, 200L))
            .get(3, TimeUnit.SECONDS));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeNonQueryStatement(sql.capture());
    assertEquals(
        "DELETE FROM telemetry WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature' AND time >= 100 AND time < 200",
        sql.getValue());
  }

  @Test
  void readExecutorDoesNotUseWriter() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = dataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("temperature", 1L, 2L, 1, "DESC");
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);
    context
        .dao()
        .remove(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("temperature", 1L, 2L))
        .get(3, TimeUnit.SECONDS);

    assertEquals(0, context.dao().stats().enqueued());
    verify(context.session(), never()).insert(any(Tablet.class));
  }

  @Test
  void destroyCompletesRunningAndQueuedReadFutures() throws Exception {
    IoTDBTableConfig config = config(10, 1000L, 100);
    config.getTs().getSave().setShutdownDrainTimeoutMs(100L);
    TestContext context = newContext(config, false);
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    when(context.session().executeQueryStatement(anyString()))
        .thenAnswer(
            invocation -> {
              readStarted.countDown();
              releaseRead.await(5, TimeUnit.SECONDS);
              return dataSet();
            });

    ListenableFuture<List<ReadTsKvQueryResult>> running =
        context
            .dao()
            .findAllAsync(
                TENANT_ID, ENTITY_ID, List.of(new BaseReadTsKvQuery("running", 1L, 2L, 1, "DESC")));
    assertTrue(readStarted.await(3, TimeUnit.SECONDS));
    ListenableFuture<List<ReadTsKvQueryResult>> queued =
        context
            .dao()
            .findAllAsync(
                TENANT_ID, ENTITY_ID, List.of(new BaseReadTsKvQuery("queued", 1L, 2L, 1, "DESC")));

    context.dao().destroy();

    assertFutureDoneWithin(running, 3, TimeUnit.SECONDS);
    assertFutureDoneWithin(queued, 3, TimeUnit.SECONDS);
    assertFutureFailsWith(running, InterruptedException.class);
    assertFutureFailsWith(queued, IoTDBTableDaoShuttingDownException.class);
    releaseRead.countDown();
  }

  @Test
  void findAllAsyncReturnsFailedFutureWhenReadQueueIsFull() throws Exception {
    IoTDBTableConfig config = config(10, 1000L, 100);
    config.getTs().getRead().setQueueCapacity(1);
    TestContext context = newContext(config, false);
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    when(context.session().executeQueryStatement(anyString()))
        .thenAnswer(
            invocation -> {
              readStarted.countDown();
              releaseRead.await(5, TimeUnit.SECONDS);
              return dataSet();
            });

    ListenableFuture<List<ReadTsKvQueryResult>> running =
        context
            .dao()
            .findAllAsync(
                TENANT_ID, ENTITY_ID, List.of(new BaseReadTsKvQuery("running", 1L, 2L, 1, "DESC")));
    assertTrue(readStarted.await(3, TimeUnit.SECONDS));
    ListenableFuture<List<ReadTsKvQueryResult>> queued =
        context
            .dao()
            .findAllAsync(
                TENANT_ID, ENTITY_ID, List.of(new BaseReadTsKvQuery("queued", 1L, 2L, 1, "DESC")));
    ListenableFuture<List<ReadTsKvQueryResult>> rejected =
        context
            .dao()
            .findAllAsync(
                TENANT_ID,
                ENTITY_ID,
                List.of(new BaseReadTsKvQuery("rejected", 1L, 2L, 1, "DESC")));

    assertFutureFailsWith(rejected, IoTDBTableReadQueueFullException.class);
    releaseRead.countDown();
    assertEquals(1, running.get(3, TimeUnit.SECONDS).size());
    assertEquals(1, queued.get(3, TimeUnit.SECONDS).size());
  }

  @Test
  void readAndDeleteReturnFailedFuturesAfterDestroy() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);

    context.dao().destroy();

    assertFutureFailsWith(
        context
            .dao()
            .findAllAsync(
                TENANT_ID,
                ENTITY_ID,
                List.of(new BaseReadTsKvQuery("after-destroy", 1L, 2L, 1, "DESC"))),
        IoTDBTableDaoShuttingDownException.class);
    assertFutureFailsWith(
        context
            .dao()
            .remove(TENANT_ID, ENTITY_ID, new BaseDeleteTsKvQuery("after-destroy", 1L, 2L)),
        IoTDBTableDaoShuttingDownException.class);
    verify(context.session(), never()).executeQueryStatement(anyString());
    verify(context.session(), never()).executeNonQueryStatement(anyString());
  }

  @Test
  void saveReturnsFailedFutureAfterDestroy() throws Exception {
    // Mirror the read/delete-after-destroy contract: once the DAO has been destroyed it must stop
    // accepting writes too, returning a failed future rather than enqueueing into a draining
    // writer.
    TestContext context = newContext(config(10, 1000L, 100), false);

    context.dao().destroy();

    assertFutureFailsWith(
        context.dao().save(TENANT_ID, ENTITY_ID, entry(1L, "after-destroy", DataType.LONG, 1L), 0),
        IoTDBTableDaoShuttingDownException.class);
    assertEquals(0, context.dao().stats().enqueued());
    verify(context.session(), never()).insert(any(Tablet.class));
  }

  private TestContext newContext(IoTDBTableConfig config, boolean startWorker)
      throws IoTDBConnectionException {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    writer = new IoTDBTableTimeseriesWriter(pool, config, startWorker);
    IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
    daos.add(dao);
    return new TestContext(dao, pool, session);
  }

  private IoTDBTableTimeseriesWriter newWriterWithClock(IoTDBTableConfig config, AtomicLong clock) {
    return new IoTDBTableTimeseriesWriter(
        mock(ITableSessionPool.class),
        config,
        false,
        new ArrayBlockingQueue<>(config.getTs().getSave().getQueueCapacity()),
        clock::get);
  }

  private IoTDBTableConfig config(int batchSize, long maxLingerMs, int queueCapacity) {
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getSave().setBatchSize(batchSize);
    config.getTs().getSave().setMaxLingerMs(maxLingerMs);
    config.getTs().getSave().setQueueCapacity(queueCapacity);
    config.getTs().getSave().setRetryInitialBackoffMs(1L);
    config.getTs().getSave().setRetryMaxBackoffMs(1L);
    config.getTs().getRead().setThreads(1);
    return config;
  }

  private Tablet insertedTablet(ITableSession session, int expectedInserts)
      throws StatementExecutionException, IoTDBConnectionException {
    ArgumentCaptor<Tablet> captor = ArgumentCaptor.forClass(Tablet.class);
    verify(session, timeout(3000).times(expectedInserts)).insert(captor.capture());
    return captor.getAllValues().get(expectedInserts - 1);
  }

  private void assertRow(Tablet tablet, int row, long ts, String key, int activeFieldIndex) {
    assertEquals(ts, tablet.getTimestamp(row));
    assertEquals(EntityType.DEVICE.name(), String.valueOf(tablet.getValue(row, 0)));
    assertEquals(TENANT_ID.getId().toString(), String.valueOf(tablet.getValue(row, 1)));
    assertEquals(key, String.valueOf(tablet.getValue(row, 2)));
    assertEquals(ENTITY_ID.getId().toString(), String.valueOf(tablet.getValue(row, 3)));
    for (int column = 4; column <= 8; column++) {
      if (column == activeFieldIndex) {
        assertFalse(tablet.isNull(row, column), "expected active field at column " + column);
      } else {
        assertTrue(tablet.isNull(row, column), "expected null inactive field at column " + column);
      }
    }
  }

  private List<String> schemaNames(Tablet tablet) {
    return tablet.getSchemas().stream().map(schema -> schema.getMeasurementName()).toList();
  }

  private List<TSDataType> schemaTypes(Tablet tablet) {
    return tablet.getSchemas().stream().map(schema -> schema.getType()).toList();
  }

  private Throwable assertFutureFailsWith(
      ListenableFuture<?> future, Class<? extends Throwable> expectedCause) throws Exception {
    ExecutionException exception =
        assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
    assertInstanceOf(expectedCause, exception.getCause());
    return exception.getCause();
  }

  private void assertFutureDoneWithin(ListenableFuture<?> future, long timeout, TimeUnit unit)
      throws Exception {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (!future.isDone() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(future.isDone(), "future did not complete within " + timeout + " " + unit);
  }

  private void assertMappedEntry(
      TsKvEntry entry, long ts, String key, DataType dataType, Object value) {
    assertInstanceOf(BasicTsKvEntry.class, entry);
    assertEquals(ts, entry.getTs());
    assertEquals(key, entry.getKey());
    assertEquals(dataType, entry.getDataType());
    assertEquals(value, entry.getValue());
    assertEquals(String.valueOf(value), entry.getValueAsString());
  }

  private SessionDataSet dataSet(MockTelemetryRow... rows)
      throws IoTDBConnectionException, StatementExecutionException {
    SessionDataSet dataSet = mock(SessionDataSet.class);
    SessionDataSet.DataIterator iterator = mock(SessionDataSet.DataIterator.class);
    AtomicInteger index = new AtomicInteger(-1);
    when(dataSet.iterator()).thenReturn(iterator);
    when(iterator.next()).thenAnswer(invocation -> index.incrementAndGet() < rows.length);
    when(iterator.isNull(anyString()))
        .thenAnswer(invocation -> rows[index.get()].isNull(invocation.getArgument(0)));
    when(iterator.getBoolean(anyString())).thenAnswer(invocation -> rows[index.get()].value());
    when(iterator.getLong(anyString())).thenAnswer(invocation -> rows[index.get()].value());
    when(iterator.getDouble(anyString())).thenAnswer(invocation -> rows[index.get()].value());
    when(iterator.getString(anyString()))
        .thenAnswer(invocation -> String.valueOf(rows[index.get()].value()));
    when(iterator.getTimestamp("time"))
        .thenAnswer(invocation -> new Timestamp(rows[index.get()].ts()));
    return dataSet;
  }

  private StatementExecutionException statementExecutionException(TSStatusCode code) {
    // The (TSStatus) constructor is the only way to carry a specific status code into the
    // exception; getStatusCode() reads it back. The TSStatus message is left unset so the
    // retry classification is driven by the code, not by any message wording.
    return new StatementExecutionException(new TSStatus(code.getStatusCode()));
  }

  private IoTDBTablePendingSave pendingSave(long ts, String key) {
    return new IoTDBTablePendingSave(
        TENANT_ID.getId().toString(),
        EntityType.DEVICE.name(),
        ENTITY_ID.getId().toString(),
        key,
        ts,
        DataType.LONG,
        1L,
        1);
  }

  private MockTelemetryRow row(long ts, String column, Object value) {
    return new MockTelemetryRow(ts, column, value);
  }

  private TestTsKvEntry entry(long ts, String key, DataType dataType, Object value) {
    return entry(ts, key, dataType, value, 1);
  }

  private TestTsKvEntry entry(
      long ts, String key, DataType dataType, Object value, int dataPoints) {
    return new TestTsKvEntry(ts, key, dataType, value, dataPoints);
  }

  private int tbDataPoints(String value) {
    return Math.max(1, (value.length() + 511) / 512);
  }

  private record MockTelemetryRow(long ts, String valueColumn, Object value) {
    private boolean isNull(String column) {
      return !valueColumn.equals(column);
    }
  }

  private record TestContext(
      IoTDBTableTimeseriesDao dao, ITableSessionPool pool, ITableSession session) {}

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

  private record TestTsKvEntry(long ts, String key, DataType dataType, Object value, int dataPoints)
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
      return dataPoints;
    }
  }

  private static final class PausingOfferQueue extends ArrayBlockingQueue<IoTDBTablePendingSave> {
    private final CountDownLatch pausedOffers;
    private final CountDownLatch releaseOffers = new CountDownLatch(1);

    private PausingOfferQueue(int capacity, int pauseCount) {
      super(capacity);
      this.pausedOffers = new CountDownLatch(pauseCount);
    }

    @Override
    public boolean offer(IoTDBTablePendingSave pending) {
      pausedOffers.countDown();
      try {
        if (!releaseOffers.await(5, TimeUnit.SECONDS)) {
          return false;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      return super.offer(pending);
    }

    private boolean awaitPausedOffers(long timeout, TimeUnit unit) throws InterruptedException {
      return pausedOffers.await(timeout, unit);
    }

    private void releaseOffers() {
      releaseOffers.countDown();
    }
  }

  private static final class DequeuedBatchLatchQueue
      extends ArrayBlockingQueue<IoTDBTablePendingSave> {
    private final CountDownLatch lingerPollStarted = new CountDownLatch(1);
    private final AtomicBoolean dequeued = new AtomicBoolean(false);

    private DequeuedBatchLatchQueue(int capacity) {
      super(capacity);
    }

    @Override
    public IoTDBTablePendingSave poll(long timeout, TimeUnit unit) throws InterruptedException {
      if (dequeued.get()) {
        lingerPollStarted.countDown();
      }
      IoTDBTablePendingSave pending = super.poll(timeout, unit);
      if (pending != null) {
        dequeued.set(true);
      }
      return pending;
    }

    private boolean awaitLingerPollStarted(long timeout, TimeUnit unit)
        throws InterruptedException {
      return lingerPollStarted.await(timeout, unit);
    }
  }

  private static final class BlockingDrainToQueue
      extends ArrayBlockingQueue<IoTDBTablePendingSave> {
    private final CountDownLatch drainToBlocked = new CountDownLatch(1);
    private final CountDownLatch releaseDrainTo = new CountDownLatch(1);

    private BlockingDrainToQueue(int capacity) {
      super(capacity);
    }

    @Override
    public int drainTo(Collection<? super IoTDBTablePendingSave> collection, int maxElements) {
      int drained = super.drainTo(collection, maxElements);
      if (drained == 0) {
        return 0;
      }
      drainToBlocked.countDown();
      boolean interrupted = false;
      while (true) {
        try {
          if (releaseDrainTo.await(100, TimeUnit.MILLISECONDS)) {
            break;
          }
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return drained;
    }

    private boolean awaitDrainToBlocked(long timeout, TimeUnit unit) throws InterruptedException {
      return drainToBlocked.await(timeout, unit);
    }

    private void releaseDrainTo() {
      releaseDrainTo.countDown();
    }
  }
}
