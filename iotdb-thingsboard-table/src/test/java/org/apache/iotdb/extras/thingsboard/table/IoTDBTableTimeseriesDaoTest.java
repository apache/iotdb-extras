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
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.AggregationParams;
import org.thingsboard.server.common.data.kv.BaseDeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
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
 * RAW (non-aggregated) READ path, the millisecond time-bucketed aggregation READ path, the DELETE
 * path and the bounded read thread-pool.
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

  @Test
  void findAllAsync_calendarSumKeepsLongTypeForLongOnlyBucket() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // On the calendar per-bucket path a long-only calendar bucket keeps the LONG SUM type, a mixed
    // calendar bucket promotes to DOUBLE. WEEK_ISO from startTs=0 yields two buckets.
    SessionDataSet week0 = aggDataSet(MockAggBucket.sum(0L, 100000000L, 30.0D, null, 2L, 0L));
    SessionDataSet week1 = aggDataSet(MockAggBucket.sum(0L, 600000000L, 4.0D, 2.5D, 1L, 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(week0, week1);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 950400000L, IntervalType.WEEK_ISO, "UTC", Aggregation.SUM);
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(2, data.size());
    assertInstanceOf(LongDataEntry.class, innerKv(data.get(0)));
    assertMappedEntry(data.get(0), 172800000L, "k", DataType.LONG, 30L);
    assertInstanceOf(DoubleDataEntry.class, innerKv(data.get(1)));
    assertMappedEntry(data.get(1), 648000000L, "k", DataType.DOUBLE, 6.5D);
  }

  @Test
  void findAllAsync_calendarMonthBuildsBoundedPerBucketSqlWithoutDateBin() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // startTs=0 (1970-01-01T00:00Z), UTC MONTH buckets: [0,Feb1), [Feb1,Mar1), [Mar1,Apr1).
    // endTs=Apr1 => endPeriod=Apr1. Three calendar buckets => THREE bounded aggregate queries,
    // each with NO date_bin / NO GROUP BY, bounded by the calendar boundary, MAX(time) projected.
    SessionDataSet bucket0 = aggDataSet();
    SessionDataSet bucket1 = aggDataSet();
    SessionDataSet bucket2 = aggDataSet();
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(bucket0, bucket1, bucket2);

    ReadTsKvQuery query =
        calendarQuery("temperature", 0L, 7776000000L, IntervalType.MONTH, "UTC", Aggregation.AVG);
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(3)).executeQueryStatement(sql.capture());
    List<String> statements = sql.getAllValues();
    assertEquals(
        "SELECT AVG(COALESCE(double_v, CAST(long_v AS DOUBLE))) AS agg_num, "
            + "MAX(time) AS max_ts FROM telemetry "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature' AND time >= 0 AND time < 2678400000",
        statements.get(0));
    assertTrue(
        statements.get(1).contains("AND time >= 2678400000 AND time < 5097600000"),
        statements.get(1));
    assertTrue(
        statements.get(2).contains("AND time >= 5097600000 AND time < 7776000000"),
        statements.get(2));
    for (String statement : statements) {
      // Calendar buckets are computed in Java; the per-bucket SQL must never use date_bin/GROUP BY.
      assertFalse(statement.contains("date_bin"), statement);
      assertFalse(statement.contains("GROUP BY"), statement);
      assertFalse(statement.contains("LIMIT"), statement);
    }
  }

  @Test
  void findAllAsync_calendarMonthMapsBucketsToCalendarMidpointEntries() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // UTC MONTH from startTs=0: bucket midpoints Jan-mid=1339200000, Feb-mid=3888000000.
    // The Mar bucket is empty (single-row dataset with NULL agg_num) and must be skipped.
    // ThingsBoard stamps each entry at the integer calendar-bucket midpoint and reports
    // lastEntryTs = MAX(underlying ts) across all buckets.
    SessionDataSet janBucket = aggDataSet(numericBucket(0L, 2000000000L, 11.5D));
    SessionDataSet febBucket = aggDataSet(numericBucket(0L, 4000000000L, 22.0D));
    SessionDataSet marBucket = aggDataSet();
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(janBucket, febBucket, marBucket);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 7776000000L, IntervalType.MONTH, "UTC", Aggregation.AVG);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    List<TsKvEntry> data = result.getData();
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 1339200000L, "k", DataType.DOUBLE, 11.5D);
    assertMappedEntry(data.get(1), 3888000000L, "k", DataType.DOUBLE, 22.0D);
    assertEquals(4000000000L, result.getLastEntryTs());
  }

  @Test
  void findAllAsync_calendarMonthFirstBucketIsPartialFromMidMonthStart() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // startTs=Jan15 1970 (1209600000) is NOT a month boundary. ThingsBoard's calculateIntervalEnd
    // advances to the START of the next month (Feb1), so the FIRST bucket is the partial
    // [Jan15, Feb1) with midpoint 1944000000 (NOT a 30-day fixed-width step). The second bucket is
    // the full calendar month [Feb1, Mar1) midpoint 3888000000.
    // SUM over double-valued data: each bucket carries a double partial sum (DoubleDataEntry).
    SessionDataSet partialBucket =
        aggDataSet(MockAggBucket.sum(0L, 1500000000L, null, 1.0D, 0L, 1L));
    SessionDataSet fullBucket = aggDataSet(MockAggBucket.sum(0L, 4000000000L, null, 2.0D, 0L, 1L));
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(partialBucket, fullBucket);

    ReadTsKvQuery query =
        calendarQuery("k", 1209600000L, 5097600000L, IntervalType.MONTH, "UTC", Aggregation.SUM);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(2)).executeQueryStatement(sql.capture());
    assertTrue(
        sql.getAllValues().get(0).contains("AND time >= 1209600000 AND time < 2678400000"),
        sql.getAllValues().get(0));
    assertTrue(
        sql.getAllValues().get(1).contains("AND time >= 2678400000 AND time < 5097600000"),
        sql.getAllValues().get(1));

    List<TsKvEntry> data = result.getData();
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 1944000000L, "k", DataType.DOUBLE, 1.0D);
    assertMappedEntry(data.get(1), 3888000000L, "k", DataType.DOUBLE, 2.0D);
  }

  @Test
  void findAllAsync_calendarWeekUsesSundayAlignedBoundaries() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // 1970-01-01 is a Thursday. WEEK (Sunday-start) from startTs=0: the first (partial) bucket runs
    // to the next Sunday 1970-01-04 (259200000), then full 7-day weeks. Midpoints 129600000 /
    // 561600000.
    SessionDataSet week0 = aggDataSet(countBucket(0L, 3L));
    SessionDataSet week1 = aggDataSet(countBucket(0L, 2L));
    SessionDataSet week2 = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(week0, week1, week2);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 1468800000L, IntervalType.WEEK, "UTC", Aggregation.COUNT);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(3)).executeQueryStatement(sql.capture());
    assertTrue(
        sql.getAllValues().get(0).contains("AND time >= 0 AND time < 259200000"),
        sql.getAllValues().get(0));
    assertTrue(
        sql.getAllValues().get(1).contains("AND time >= 259200000 AND time < 864000000"),
        sql.getAllValues().get(1));

    List<TsKvEntry> data = result.getData();
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 129600000L, "k", DataType.LONG, 3L);
    assertMappedEntry(data.get(1), 561600000L, "k", DataType.LONG, 2L);
  }

  @Test
  void findAllAsync_calendarCountAppliesDominantTypePriority() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // The calendar path reuses the typed-COUNT dominant-column priority (bool > str > json >
    // long+double). Bucket 0 string wins (countStr=2 despite numeric); bucket 1 numeric only.
    SessionDataSet strDominant =
        aggDataSet(MockAggBucket.typedCount(0L, 2000000000L, 0L, 2L, 0L, 9L, 9L));
    SessionDataSet numericOnly =
        aggDataSet(MockAggBucket.typedCount(0L, 4000000000L, 0L, 0L, 0L, 4L, 1L));
    SessionDataSet emptyBucket = aggDataSet();
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(strDominant, numericOnly, emptyBucket);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 7776000000L, IntervalType.MONTH, "UTC", Aggregation.COUNT);
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 1339200000L, "k", DataType.LONG, 2L);
    assertMappedEntry(data.get(1), 3888000000L, "k", DataType.LONG, 5L);
  }

  @Test
  void findAllAsync_calendarEmptyResultFallsBackToStartTsAndIgnoresLimitOrder() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // No data in any calendar bucket: lastEntryTs falls back to startTs and order/limit are ignored
    // (a DESC + LIMIT 0 calendar query still walks and queries every bucket).
    SessionDataSet empty0 = aggDataSet();
    SessionDataSet empty1 = aggDataSet();
    SessionDataSet empty2 = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(empty0, empty1, empty2);

    ReadTsKvQuery query =
        new BaseReadTsKvQuery(
            "k",
            0L,
            7776000000L,
            AggregationParams.calendar(Aggregation.AVG, IntervalType.MONTH, "UTC"),
            0,
            "DESC");
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    assertEquals(List.of(), result.getData());
    assertEquals(0L, result.getLastEntryTs());
    // One bounded query per calendar bucket (3): the zero limit did not short-circuit.
    verify(context.session(), times(3)).executeQueryStatement(anyString());
  }

  @Test
  void findAllAsync_millisecondsIntervalStillRoutesToDateBinPath() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet groupedDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(groupedDataSet);

    // An explicit MILLISECONDS interval type must keep the single grouped date_bin SQL (one query,
    // GROUP BY, ORDER BY 1 ASC) and must NOT be routed to the per-bucket calendar path.
    ReadTsKvQuery query = calendarLikeMilliseconds("k", 0L, 100L, 25L, Aggregation.AVG);
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(1)).executeQueryStatement(sql.capture());
    String statement = sql.getValue();
    assertTrue(statement.contains("date_bin(25ms, time, 0) AS bucket_ts"), statement);
    assertTrue(statement.endsWith("GROUP BY 1 ORDER BY 1 ASC"), statement);
  }

  @Test
  void findAllAsync_calendarCountSkipsRealShapedEmptyMiddleBucket() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // UTC MONTH over [0, Apr1=7776000000): three calendar buckets Jan/Feb/Mar. The MIDDLE month
    // (Feb) is the REAL empty shape IoTDB returns for an empty bounded window: ONE row with every
    // typed COUNT = 0 and MAX(time) NULL (emptyAggRow). The other two months have data. The
    // calendar reader's `!isNull(max_ts)` guard skips the empty middle bucket, so the spurious
    // COUNT=0 LongDataEntry is NOT emitted. Without that guard COUNT would leak a third entry
    // (count 0) at the Feb midpoint -> 3 entries instead of 2.
    SessionDataSet janBucket = aggDataSet(countBucket(0L, 3L));
    SessionDataSet febEmpty = aggDataSet(emptyAggRow(2678400000L));
    SessionDataSet marBucket = aggDataSet(countBucket(5097600000L, 5L));
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(janBucket, febEmpty, marBucket);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 7776000000L, IntervalType.MONTH, "UTC", Aggregation.COUNT);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    List<TsKvEntry> data = result.getData();
    // EXACTLY two entries: the empty Feb bucket is SKIPPED, never emitted as count 0.
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 1339200000L, "k", DataType.LONG, 3L); // Jan midpoint
    assertMappedEntry(data.get(1), 6436800000L, "k", DataType.LONG, 5L); // Mar midpoint
    // All three calendar buckets were queried (the empty one was queried but dropped in Java).
    verify(context.session(), times(3)).executeQueryStatement(anyString());
  }

  @Test
  void findAllAsync_calendarAvgSkipsRealShapedEmptyMiddleBucket() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // Regression guard that the universal empty-bucket skip did not break the AVG path: the REAL
    // empty middle bucket here has NULL agg_num AND NULL max_ts (emptyAggRow). The reader skips it
    // on the `!isNull(max_ts)` guard exactly as it does for COUNT, so AVG returns the two non-empty
    // months only.
    SessionDataSet janBucket = aggDataSet(numericBucket(0L, 2000000000L, 11.5D));
    SessionDataSet febEmpty = aggDataSet(emptyAggRow(2678400000L));
    SessionDataSet marBucket = aggDataSet(numericBucket(5097600000L, 6000000000L, 22.0D));
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(janBucket, febEmpty, marBucket);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 7776000000L, IntervalType.MONTH, "UTC", Aggregation.AVG);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    List<TsKvEntry> data = result.getData();
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 1339200000L, "k", DataType.DOUBLE, 11.5D); // Jan midpoint
    assertMappedEntry(data.get(1), 6436800000L, "k", DataType.DOUBLE, 22.0D); // Mar midpoint
    // lastEntryTs = MAX(underlying ts) across non-empty buckets; the empty Feb row never updates
    // it.
    assertEquals(6000000000L, result.getLastEntryTs());
    verify(context.session(), times(3)).executeQueryStatement(anyString());
  }

  @Test
  void findAllAsync_millisecondsFactoryRoutesToDateBinPath() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet groupedDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(groupedDataSet);

    // Real-TB MILLISECONDS routing: a query built the way ThingsBoard actually builds a fixed-width
    // aggregation (AggregationParams.milliseconds carries IntervalType.MILLISECONDS + a positive
    // interval) routes to the date_bin MILLISECONDS path. A null-IntervalType non-NONE aggregation
    // is NOT a real-TB scenario (real TB always pairs a non-NONE aggregation with a concrete
    // IntervalType), and getInterval() returns 0L for a null type matching real TB v4.3.1.2, so the
    // MS path's interval<=0 guard would (correctly) reject it; this test therefore exercises the
    // REAL milliseconds() factory instead.
    AggregationParams msParams = AggregationParams.milliseconds(Aggregation.AVG, 25);
    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 100L, msParams, 10);
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(1)).executeQueryStatement(sql.capture());
    String statement = sql.getValue();
    // MILLISECONDS path with the 25ms interval, anchored at startTs=0.
    assertTrue(statement.contains("date_bin(25ms, time, 0) AS bucket_ts"), statement);
    assertTrue(statement.endsWith("GROUP BY 1 ORDER BY 1 ASC"), statement);
  }

  @Test
  void findAllAsync_calendarWeekIsoRoutesToBoundedPerBucketSql() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // 1970-01-01 is a Thursday. WEEK_ISO (Monday-start) from startTs=0: first ISO boundary is
    // Monday
    // 1970-01-05 (345600000), then the full ISO week to 1970-01-12 (950400000). Two buckets =>
    // TWO bounded aggregate queries, each with NO date_bin / NO GROUP BY, bounded by the ISO
    // boundaries from TimeUtils.calculateIntervalEnd. Midpoints 172800000 / 648000000.
    SessionDataSet week0 = aggDataSet(countBucket(0L, 4L));
    SessionDataSet week1 = aggDataSet(countBucket(0L, 2L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(week0, week1);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 950400000L, IntervalType.WEEK_ISO, "UTC", Aggregation.COUNT);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(2)).executeQueryStatement(sql.capture());
    List<String> statements = sql.getAllValues();
    assertTrue(statements.get(0).contains("AND time >= 0 AND time < 345600000"), statements.get(0));
    assertTrue(
        statements.get(1).contains("AND time >= 345600000 AND time < 950400000"),
        statements.get(1));
    for (String statement : statements) {
      assertFalse(statement.contains("date_bin"), statement);
      assertFalse(statement.contains("GROUP BY"), statement);
      assertFalse(statement.contains("LIMIT"), statement);
    }

    List<TsKvEntry> data = result.getData();
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 172800000L, "k", DataType.LONG, 4L);
    assertMappedEntry(data.get(1), 648000000L, "k", DataType.LONG, 2L);
  }

  @Test
  void findAllAsync_calendarQuarterRoutesToBoundedPerBucketSql() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // QUARTER from startTs=0 (1970-01-01 = Q1 start): next quarter boundary is 1970-04-01
    // (7776000000), then 1970-07-01 (15638400000). Two buckets => TWO bounded aggregate queries,
    // each with NO date_bin / NO GROUP BY, bounded by the quarter boundaries from
    // TimeUtils.calculateIntervalEnd. Midpoints 3888000000 / 11707200000.
    // SUM over double-valued data: each quarter carries a double partial sum (DoubleDataEntry).
    SessionDataSet q0 = aggDataSet(MockAggBucket.sum(0L, 1000000000L, null, 10.0D, 0L, 1L));
    SessionDataSet q1 = aggDataSet(MockAggBucket.sum(0L, 9000000000L, null, 20.0D, 0L, 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(q0, q1);

    ReadTsKvQuery query =
        calendarQuery("k", 0L, 15638400000L, IntervalType.QUARTER, "UTC", Aggregation.SUM);
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(2)).executeQueryStatement(sql.capture());
    List<String> statements = sql.getAllValues();
    assertTrue(
        statements.get(0).contains("AND time >= 0 AND time < 7776000000"), statements.get(0));
    assertTrue(
        statements.get(1).contains("AND time >= 7776000000 AND time < 15638400000"),
        statements.get(1));
    for (String statement : statements) {
      assertFalse(statement.contains("date_bin"), statement);
      assertFalse(statement.contains("GROUP BY"), statement);
      assertFalse(statement.contains("LIMIT"), statement);
    }

    List<TsKvEntry> data = result.getData();
    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 3888000000L, "k", DataType.DOUBLE, 10.0D);
    assertMappedEntry(data.get(1), 11707200000L, "k", DataType.DOUBLE, 20.0D);
  }

  @Test
  void findAllAsync_avgBuildsStartTsAnchoredBucketedSql() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    // DESC order + LIMIT 10 are intentionally ignored for aggregation (ThingsBoard contract):
    // buckets are anchored at startTs=0, MAX(time) is projected for lastEntryTs, and the SQL is
    // ordered ascending with no LIMIT.
    ReadTsKvQuery query = new BaseReadTsKvQuery("temperature", 0L, 100L, 25L, 10, Aggregation.AVG);
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertEquals(
        "SELECT date_bin(25ms, time, 0) AS bucket_ts, "
            + "AVG(COALESCE(double_v, CAST(long_v AS DOUBLE))) AS agg_num, "
            + "MAX(time) AS max_ts FROM telemetry "
            + "WHERE tenant_id='11111111-1111-1111-1111-111111111111' "
            + "AND entity_type='DEVICE' "
            + "AND entity_id='22222222-2222-2222-2222-222222222222' "
            + "AND key='temperature' AND time >= 0 AND time < 100 "
            + "GROUP BY 1 ORDER BY 1 ASC",
        sql.getValue());
  }

  @Test
  void findAllAsync_aggregationZeroWidthRangeStillWalksOneBucket() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    // ThingsBoard clamps endPeriod = max(startTs + 1, endTs); a zero-width [50, 50] aggregation
    // query
    // must still scan [50, 51) so a point at startTs is included rather than dropped by time < 50.
    ReadTsKvQuery query = new BaseReadTsKvQuery("temperature", 50L, 50L, 25L, 10, Aggregation.AVG);
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertTrue(sql.getValue().contains("AND time >= 50 AND time < 51 "), sql.getValue());
  }

  @Test
  void findAllAsync_sumCountMinMaxBuildSql() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet sumCountMinMaxDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(sumCountMinMaxDataSet);

    ReadTsKvQuery sum = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.SUM, "ASC");
    ReadTsKvQuery count = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.COUNT, "ASC");
    ReadTsKvQuery min = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.MIN, "ASC");
    ReadTsKvQuery max = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.MAX, "ASC");

    context
        .dao()
        .findAllAsync(TENANT_ID, ENTITY_ID, List.of(sum, count, min, max))
        .get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(4)).executeQueryStatement(sql.capture());
    List<String> statements = sql.getAllValues();
    // SUM projects per-type partial sums (the long partial as SUM(CAST(long_v AS DOUBLE)) -- a
    // DOUBLE, NEVER CAST(SUM AS INT64) which would THROW an out-of-range error when the long-only
    // sum
    // exceeds Long.MAX) + long/double non-null counts so the row mapper can keep a long-only SUM
    // LONG-typed and promote a mixed bucket to DOUBLE. It also projects MIN(long_v)/MAX(long_v) so
    // the mapper can bound the long sum and trust the double partial only while count_long
    // * maxAbs <= 2^53, falling back to an exact Java re-sum otherwise.
    assertTrue(
        statements.get(0).contains("SUM(CAST(long_v AS DOUBLE)) AS sum_long")
            && statements.get(0).contains("SUM(double_v) AS sum_double")
            && statements.get(0).contains("MIN(long_v) AS min_long")
            && statements.get(0).contains("MAX(long_v) AS max_long")
            && statements
                .get(0)
                .contains(
                    "CAST(SUM(CASE WHEN long_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_long")
            && statements
                .get(0)
                .contains(
                    "CAST(SUM(CASE WHEN double_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_double"),
        statements.get(0));
    assertFalse(
        statements.get(0).contains("SUM(COALESCE(double_v, CAST(long_v AS DOUBLE)))"),
        statements.get(0));
    // COUNT projects ThingsBoard's per-type non-null counters (not COUNT(*)).
    assertTrue(
        statements
                .get(1)
                .contains(
                    "CAST(SUM(CASE WHEN bool_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_bool")
            && statements
                .get(1)
                .contains(
                    "CAST(SUM(CASE WHEN str_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_str")
            && statements
                .get(1)
                .contains(
                    "CAST(SUM(CASE WHEN json_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_json")
            && statements
                .get(1)
                .contains(
                    "CAST(SUM(CASE WHEN long_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_long")
            && statements
                .get(1)
                .contains(
                    "CAST(SUM(CASE WHEN double_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_double"),
        statements.get(1));
    assertFalse(statements.get(1).contains("COUNT(*)"), statements.get(1));
    // MIN/MAX project the numeric + string aggregates AND a direct MIN(long_v)/MAX(long_v) channel
    // AND the long/double non-null counts. The long channel is EXACT for a long-only bucket (it
    // SELECTs a stored long, no double round-trip); the counts let the row mapper keep a
    // long-only MIN/MAX LONG-typed and promote a mixed bucket to DOUBLE.
    assertTrue(
        statements.get(2).contains("MIN(COALESCE(double_v, CAST(long_v AS DOUBLE))) AS agg_num")
            && statements.get(2).contains("MIN(long_v) AS min_long")
            && statements.get(2).contains("MIN(str_v) AS agg_str")
            && statements
                .get(2)
                .contains(
                    "CAST(SUM(CASE WHEN long_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_long")
            && statements
                .get(2)
                .contains(
                    "CAST(SUM(CASE WHEN double_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_double"),
        statements.get(2));
    // The numeric MAX is projected as -MIN(-x): IoTDB's grouped max accumulator seeds
    // FLOAT/DOUBLE state with Double.MIN_VALUE (the smallest POSITIVE value), so MAX over a
    // bucket whose maximum is zero or negative returns NULL and the bucket would be dropped
    // (apache/iotdb#18300). The grouped MIN accumulator is unaffected and IEEE-754 negation is
    // exact, so this is an exact substitute over finite values. The long and string channels are
    // already correct and keep using MAX.
    assertTrue(
        statements
                .get(3)
                .contains("-1 * MIN(-1 * (COALESCE(double_v, CAST(long_v AS DOUBLE)))) AS agg_num")
            && statements.get(3).contains("MAX(long_v) AS max_long")
            && statements.get(3).contains("MAX(str_v) AS agg_str")
            && statements
                .get(3)
                .contains(
                    "CAST(SUM(CASE WHEN long_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_long")
            && statements
                .get(3)
                .contains(
                    "CAST(SUM(CASE WHEN double_v IS NOT NULL THEN 1 ELSE 0 END) AS INT64) "
                        + "AS count_double"),
        statements.get(3));
    for (String statement : statements) {
      // startTs=0 anchored buckets, MAX(time) for lastEntryTs, ascending, no LIMIT (order/limit
      // ignored for aggregation).
      assertTrue(statement.contains("date_bin(30ms, time, 0) AS bucket_ts"), statement);
      assertTrue(statement.contains("MAX(time) AS max_ts"), statement);
      assertTrue(statement.endsWith("GROUP BY 1 ORDER BY 1 ASC"), statement);
      assertFalse(statement.contains("LIMIT"), statement);
    }
  }

  @Test
  void findAllAsync_avgMapsNumericBucketsToMidpointDoubleEntries() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // startTs=0, interval=30, endTs=90 -> bucket starts 0/30/60, all full width.
    // TB midpoints: [0,30)->15, [30,60)->45, [60,90)->75. lastEntryTs = MAX(time) = 80.
    SessionDataSet dataSet =
        aggDataSet(
            numericBucket(0L, 25L, 11.5D),
            numericBucket(30L, 55L, 30.0D),
            numericBucket(60L, 80L, 7.25D));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 90L, 30L, 10, Aggregation.AVG, "ASC");
    ReadTsKvQueryResult result =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0);

    List<TsKvEntry> data = result.getData();
    assertEquals(3, data.size());
    assertMappedEntry(data.get(0), 15L, "k", DataType.DOUBLE, 11.5D);
    assertMappedEntry(data.get(1), 45L, "k", DataType.DOUBLE, 30.0D);
    assertMappedEntry(data.get(2), 75L, "k", DataType.DOUBLE, 7.25D);
    assertEquals(80L, result.getLastEntryTs());
  }

  @Test
  void findAllAsync_lastBucketMidpointIsClampedToEndTs() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // startTs=0, interval=30, endTs=50 -> endPeriod = max(1, 50) = 50.
    // Bucket [0,30): full width -> midpoint 0 + (30-0)/2 = 15.
    // Bucket [30,50): END-CLAMPED -> bucketEnd = min(30+30, 50) = 50,
    //                 midpoint 30 + (50-30)/2 = 40 (NOT the full-width 45).
    SessionDataSet dataSet =
        aggDataSet(numericBucket(0L, 20L, 1.0D), numericBucket(30L, 45L, 2.0D));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 50L, 30L, 10, Aggregation.AVG, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 15L, "k", DataType.DOUBLE, 1.0D);
    assertMappedEntry(data.get(1), 40L, "k", DataType.DOUBLE, 2.0D);
  }

  @Test
  void findAllAsync_countMapsTypedCountToMidpointLongEntries() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // startTs=0, interval=30, endTs=60 -> bucket starts 0/30 -> TB midpoints 15/45.
    // Each single-typed (long_v) bucket's typed count equals its row count.
    SessionDataSet dataSet = aggDataSet(countBucket(0L, 3L), countBucket(30L, 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.COUNT, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(2, data.size());
    assertMappedEntry(data.get(0), 15L, "k", DataType.LONG, 3L);
    assertMappedEntry(data.get(1), 45L, "k", DataType.LONG, 1L);
  }

  @Test
  void findAllAsync_countAppliesThingsBoardDominantTypePriority() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // ThingsBoard reports the FIRST non-zero typed counter in priority order:
    //   boolean -> string -> json -> (long + double).
    // Bucket 0: only boolean populated (countBool=4) despite numeric counters set -> boolean wins.
    // Bucket 1: no boolean, string populated (countStr=2) despite json/long/double -> string wins.
    // Bucket 2: no bool/str, json populated (countJson=5) despite long/double -> json wins.
    // Bucket 3: only numeric populated (long=2, double=3) -> long+double=5.
    SessionDataSet dataSet =
        aggDataSet(
            MockAggBucket.typedCount(0L, 0L, 4L, 9L, 9L, 9L, 9L),
            MockAggBucket.typedCount(30L, 30L, 0L, 2L, 9L, 9L, 9L),
            MockAggBucket.typedCount(60L, 60L, 0L, 0L, 5L, 9L, 9L),
            MockAggBucket.typedCount(90L, 90L, 0L, 0L, 0L, 2L, 3L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 120L, 30L, 10, Aggregation.COUNT, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(4, data.size());
    // startTs=0, interval=30, endTs=120 -> midpoints 15/45/75/105.
    assertMappedEntry(data.get(0), 15L, "k", DataType.LONG, 4L);
    assertMappedEntry(data.get(1), 45L, "k", DataType.LONG, 2L);
    assertMappedEntry(data.get(2), 75L, "k", DataType.LONG, 5L);
    assertMappedEntry(data.get(3), 105L, "k", DataType.LONG, 5L);
  }

  @Test
  void findAllAsync_minMaxPickNumericOrStringPerBucket() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet numericDataSet = aggDataSet(numericBucket(0L, 5.5D), numericBucket(30L, 30.0D));
    SessionDataSet stringDataSet =
        aggDataSet(stringBucket(0L, "apple"), stringBucket(30L, "cherry"));
    when(context.session().executeQueryStatement(anyString()))
        .thenReturn(numericDataSet, stringDataSet);

    ReadTsKvQuery numeric = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.MIN, "ASC");
    ReadTsKvQuery string = new BaseReadTsKvQuery("sk", 0L, 60L, 30L, 10, Aggregation.MIN, "ASC");
    List<ReadTsKvQueryResult> results =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(numeric, string))
            .get(3, TimeUnit.SECONDS);

    // startTs=0, interval=30, endTs=60 -> bucket starts 0/30 -> TB midpoints 15/45.
    List<TsKvEntry> numericData = results.get(0).getData();
    assertEquals(2, numericData.size());
    assertMappedEntry(numericData.get(0), 15L, "k", DataType.DOUBLE, 5.5D);
    assertMappedEntry(numericData.get(1), 45L, "k", DataType.DOUBLE, 30.0D);

    List<TsKvEntry> stringData = results.get(1).getData();
    assertEquals(2, stringData.size());
    assertMappedEntry(stringData.get(0), 15L, "sk", DataType.STRING, "apple");
    assertMappedEntry(stringData.get(1), 45L, "sk", DataType.STRING, "cherry");
  }

  @Test
  void findAllAsync_sumKeepsLongTypeForLongOnlyBucketAndDoubleForMixed() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // ThingsBoard 4.3.1.2 returns a LONG-typed SUM when only long values participate in a
    // bucket and a DOUBLE only when a double participates.
    //   Bucket [0,30):  long-only  -> sum_long=5, no doubles  -> LongDataEntry(5)
    //   Bucket [30,60): mixed       -> sum_long=4, sum_double=1.5, 1 double row ->
    // DoubleDataEntry(5.5)
    SessionDataSet dataSet =
        aggDataSet(
            MockAggBucket.sum(0L, 20L, 5.0D, null, 2L, 0L),
            MockAggBucket.sum(30L, 55L, 4.0D, 1.5D, 1L, 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.SUM, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(2, data.size());
    // Long-only SUM keeps the LONG type.
    assertInstanceOf(LongDataEntry.class, innerKv(data.get(0)));
    assertEquals(DataType.LONG, data.get(0).getDataType());
    assertMappedEntry(data.get(0), 15L, "k", DataType.LONG, 5L);
    // Mixed bucket promotes to DOUBLE, summing the long and double partials (4 + 1.5 = 5.5).
    assertInstanceOf(DoubleDataEntry.class, innerKv(data.get(1)));
    assertEquals(DataType.DOUBLE, data.get(1).getDataType());
    assertMappedEntry(data.get(1), 45L, "k", DataType.DOUBLE, 5.5D);
  }

  @Test
  void findAllAsync_minMaxKeepLongTypeForLongOnlyBucketAndDoubleForMixed() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // Long-only MIN/MAX keep the LONG type; a bucket with any participating double stays
    // DOUBLE with the SAME numeric value as before.
    //   Bucket [0,30):  long-only  (countLong=3, countDouble=0) -> LongDataEntry((long) agg_num)
    //   Bucket [30,60): mixed      (countLong=2, countDouble=1) -> DoubleDataEntry(agg_num)
    SessionDataSet minDataSet =
        aggDataSet(
            MockAggBucket.typedNumeric(0L, 20L, 5.0D, 3L, 0L),
            MockAggBucket.typedNumeric(30L, 55L, 7.5D, 2L, 1L));
    SessionDataSet maxDataSet =
        aggDataSet(
            MockAggBucket.typedNumeric(0L, 20L, 9.0D, 3L, 0L),
            MockAggBucket.typedNumeric(30L, 55L, 12.5D, 2L, 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(minDataSet, maxDataSet);

    ReadTsKvQuery min = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.MIN, "ASC");
    ReadTsKvQuery max = new BaseReadTsKvQuery("k", 0L, 60L, 30L, 10, Aggregation.MAX, "ASC");
    List<ReadTsKvQueryResult> results =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(min, max))
            .get(3, TimeUnit.SECONDS);

    List<TsKvEntry> minData = results.get(0).getData();
    assertEquals(2, minData.size());
    assertInstanceOf(LongDataEntry.class, innerKv(minData.get(0)));
    assertMappedEntry(minData.get(0), 15L, "k", DataType.LONG, 5L);
    assertInstanceOf(DoubleDataEntry.class, innerKv(minData.get(1)));
    assertMappedEntry(minData.get(1), 45L, "k", DataType.DOUBLE, 7.5D);

    List<TsKvEntry> maxData = results.get(1).getData();
    assertEquals(2, maxData.size());
    assertInstanceOf(LongDataEntry.class, innerKv(maxData.get(0)));
    assertMappedEntry(maxData.get(0), 15L, "k", DataType.LONG, 9L);
    assertInstanceOf(DoubleDataEntry.class, innerKv(maxData.get(1)));
    assertMappedEntry(maxData.get(1), 45L, "k", DataType.DOUBLE, 12.5D);
  }

  @Test
  void findAllAsync_minMaxLongOnlyReadExactLongChannelAbove2Pow53() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // A long-only MIN/MAX must come back EXACT even above 2^53. The stored long is
    // 9007199254740993 (= 2^53 + 1), which is NOT representable as a double: COALESCE(double_v,
    // CAST(long_v AS DOUBLE)) would round it to 9007199254740992.0 (agg_num below). The DAO must
    // read the direct MIN(long_v)/MAX(long_v) channel instead, yielding the exact long. The
    // (long) getDouble(agg_num) round-trip would return ...992 and FAIL this test.
    long exact = 9007199254740993L; // 2^53 + 1
    double roundedAggNum = 9007199254740992.0D; // what the COALESCE->DOUBLE path would expose
    SessionDataSet minDataSet =
        aggDataSet(MockAggBucket.minMaxLong(0L, 20L, roundedAggNum, exact, 1L));
    SessionDataSet maxDataSet =
        aggDataSet(MockAggBucket.minMaxLong(0L, 20L, roundedAggNum, exact, 1L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(minDataSet, maxDataSet);

    ReadTsKvQuery min = new BaseReadTsKvQuery("k", 0L, 30L, 30L, 10, Aggregation.MIN, "ASC");
    ReadTsKvQuery max = new BaseReadTsKvQuery("k", 0L, 30L, 30L, 10, Aggregation.MAX, "ASC");
    List<ReadTsKvQueryResult> results =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(min, max))
            .get(3, TimeUnit.SECONDS);

    TsKvEntry minEntry = results.get(0).getData().get(0);
    assertInstanceOf(LongDataEntry.class, innerKv(minEntry));
    assertEquals(DataType.LONG, minEntry.getDataType());
    assertEquals(
        Optional.of(exact), minEntry.getLongValue(), "MIN must be the exact long, not ...992");
    TsKvEntry maxEntry = results.get(1).getData().get(0);
    assertInstanceOf(LongDataEntry.class, innerKv(maxEntry));
    assertEquals(DataType.LONG, maxEntry.getDataType());
    assertEquals(
        Optional.of(exact), maxEntry.getLongValue(), "MAX must be the exact long, not ...992");
  }

  @Test
  void findAllAsync_sumLongOnlyFastPathWhenBoundWithin2Pow53() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // Fast path: a long-only bucket whose conservative bound count_long * maxAbs stays
    // within 2^53 is provably exact, so the DAO trusts the DOUBLE-projected sum_long (cast back to
    // long, lossless within the bound) and does NOT re-query. count_long=3, maxAbs=40 -> 120 <=
    // 2^53,
    // fast path. Exactly ONE aggregate query.
    SessionDataSet dataSet = aggDataSet(MockAggBucket.sumWithBound(0L, 20L, 90.0D, 3L, 20L, 40L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 30L, 30L, 10, Aggregation.SUM, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(1, data.size());
    assertInstanceOf(LongDataEntry.class, innerKv(data.get(0)));
    assertMappedEntry(data.get(0), 15L, "k", DataType.LONG, 90L);
    // Fast path: no raw long_v re-query (exactly one aggregate statement was issued).
    verify(context.session(), times(1)).executeQueryStatement(anyString());
  }

  @Test
  void findAllAsync_sumLongOnlyReSumsExactlyWhenBoundExceeds2Pow53() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // Fallback: a long-only bucket whose bound count_long * maxAbs MAY exceed 2^53 cannot
    // trust the DOUBLE-accumulated SUM, so the DAO re-queries the raw long_v values and sums them
    // in
    // Java as long. Here the two stored values are 9007199254740993 (2^53 + 1) and 1000; their
    // EXACT
    // long sum is 9007199254741993. IoTDB's double accumulator would have returned 9007199254741992
    // (the rounded sum_long we deliberately mock), so trusting it would FAIL. maxAbs =
    // 9007199254740993
    // and count_long = 2, so count_long > 2^53 / maxAbs -> the bound check forces the fallback.
    long bigValue = 9007199254740993L; // 2^53 + 1
    long exactSum = 9007199254741993L; // bigValue + 1000, exact long arithmetic
    long roundedDoubleSum =
        9007199254741992L; // what IoTDB's DOUBLE accumulator would have produced
    SessionDataSet aggDataSet =
        aggDataSet(MockAggBucket.sumWithBound(0L, 20L, roundedDoubleSum, 2L, 1000L, bigValue));
    SessionDataSet rawLong = rawLongDataSet(bigValue, 1000L);
    when(context.session().executeQueryStatement(anyString()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              // The aggregate uses GROUP BY; the exact re-sum selects the raw long_v column.
              return sql.contains("GROUP BY") ? aggDataSet : rawLong;
            });

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 30L, 30L, 10, Aggregation.SUM, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(1, data.size());
    assertInstanceOf(LongDataEntry.class, innerKv(data.get(0)));
    assertEquals(DataType.LONG, data.get(0).getDataType());
    // EXACT Java re-sum, not the rounded double sum the accumulator would have produced.
    assertEquals(
        Optional.of(exactSum),
        data.get(0).getLongValue(),
        "long-only SUM > 2^53 must be the exact Java re-sum, not the rounded double sum");
    // The fallback issued the raw long_v re-query in the same bucket window.
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), times(2)).executeQueryStatement(sql.capture());
    String reQuery =
        sql.getAllValues().stream().filter(s -> !s.contains("GROUP BY")).findFirst().orElseThrow();
    assertTrue(reQuery.contains("SELECT long_v FROM telemetry"), reQuery);
    assertTrue(reQuery.contains("long_v IS NOT NULL"), reQuery);
    // The re-query window is the FULL date_bin bucket [startTs, startTs + interval) = [0, 30).
    assertTrue(reQuery.contains("time >= 0") && reQuery.contains("time < 30"), reQuery);
  }

  @Test
  void findAllAsync_sumFallbackClampsFinalBucketReQueryToEndPeriodNotFullWidth() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // Regression for a final, end-clamped MS bucket that triggers the SUM re-sum fallback: the
    // re-query MUST cover exactly the rows the date_bin aggregate counted ([bucketStart,
    // endPeriod)),
    // not the full physical bucket [bucketStart, bucketStart + interval). Query [0, 2500) interval
    // 1000 -> the last bucket date_bin=2000 is clamped to endPeriod=2500; a full-width [2000, 3000)
    // re-query would wrongly include rows beyond endTs and over-count the sum.
    long bigValue = 9007199254740993L; // 2^53 + 1 -> forces the fallback
    long exactSum = 9007199254741993L; // bigValue + 1000
    long roundedDoubleSum = 9007199254741992L;
    SessionDataSet aggDataSet =
        aggDataSet(MockAggBucket.sumWithBound(2000L, 2400L, roundedDoubleSum, 2L, 1000L, bigValue));
    SessionDataSet rawLong = rawLongDataSet(bigValue, 1000L);
    when(context.session().executeQueryStatement(anyString()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              return sql.contains("GROUP BY") ? aggDataSet : rawLong;
            });

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 2500L, 1000L, 10, Aggregation.SUM, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(1, data.size());
    assertEquals(Optional.of(exactSum), data.get(0).getLongValue());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), times(2)).executeQueryStatement(sql.capture());
    String reQuery =
        sql.getAllValues().stream().filter(s -> !s.contains("GROUP BY")).findFirst().orElseThrow();
    // CLAMPED to endPeriod (2500), matching the aggregate's `time < endPeriod` filter -- NOT the
    // full-width bucketStart + interval (3000).
    assertTrue(reQuery.contains("time >= 2000") && reQuery.contains("time < 2500"), reQuery);
    assertFalse(reQuery.contains("time < 3000"), reQuery);
  }

  @Test
  void findAllAsync_avgStaysDoubleForLongOnlyBucket() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    // AVG is ALWAYS DOUBLE in ThingsBoard, even for a long-only bucket.
    SessionDataSet dataSet = aggDataSet(MockAggBucket.typedNumeric(0L, 20L, 7.0D, 3L, 0L));
    when(context.session().executeQueryStatement(anyString())).thenReturn(dataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 30L, 30L, 10, Aggregation.AVG, "ASC");
    List<TsKvEntry> data =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(query))
            .get(3, TimeUnit.SECONDS)
            .get(0)
            .getData();

    assertEquals(1, data.size());
    assertInstanceOf(DoubleDataEntry.class, innerKv(data.get(0)));
    assertMappedEntry(data.get(0), 15L, "k", DataType.DOUBLE, 7.0D);
  }

  @Test
  void findAllAsync_aggregationSkipsEmptyBucketsAndIgnoresLimit() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet emptyDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(emptyDataSet);

    ReadTsKvQuery noBuckets = new BaseReadTsKvQuery("k", 100L, 400L, 100L, 10, Aggregation.AVG);
    ReadTsKvQueryResult emptyResult =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(noBuckets))
            .get(3, TimeUnit.SECONDS)
            .get(0);
    assertEquals(List.of(), emptyResult.getData());
    // No data matched -> lastEntryTs falls back to startTs (ThingsBoard contract).
    assertEquals(100L, emptyResult.getLastEntryTs());

    // ThingsBoard ignores the query limit for aggregation: a zero limit must NOT short-circuit;
    // the aggregate is still issued and returns every non-empty bucket (here: none).
    ReadTsKvQuery zeroLimit = new BaseReadTsKvQuery("k", 100L, 400L, 100L, 0, Aggregation.AVG);
    ReadTsKvQueryResult zeroLimitResult =
        context
            .dao()
            .findAllAsync(TENANT_ID, ENTITY_ID, List.of(zeroLimit))
            .get(3, TimeUnit.SECONDS)
            .get(0);
    assertEquals(List.of(), zeroLimitResult.getData());
    assertEquals(100L, zeroLimitResult.getLastEntryTs());
    // Both queries issue SQL: limit is not consulted for aggregation.
    verify(context.session(), times(2)).executeQueryStatement(anyString());
  }

  @Test
  void findAllAsync_aggregationWithSubOneIntervalRoutesToRawLikeThingsBoard() throws Exception {
    // ThingsBoard 4.3.1.2 (AbstractChunkedAggregationTimeseriesDao.findAllAsync) routes to the RAW
    // findAllWithLimit path when aggregation == NONE OR interval < 1. An AVG query with interval 0
    // must
    // therefore return RAW telemetry (a plain typed-column SELECT with ORDER BY time + LIMIT), NOT
    // be
    // rejected and NOT build a date_bin aggregation.
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet rawDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(rawDataSet);

    ReadTsKvQuery query = new BaseReadTsKvQuery("k", 0L, 100L, 0L, 10, Aggregation.AVG);
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(query)).get(3, TimeUnit.SECONDS);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    String captured = sql.getValue();
    assertTrue(
        captured.contains("SELECT time, bool_v, long_v, double_v, str_v, json_v FROM telemetry"),
        captured);
    assertTrue(captured.contains("LIMIT 10"), captured);
    assertTrue(!captured.contains("date_bin"), captured);
  }

  @Test
  void findAllAsync_aggregationEscapesKeyAndIgnoresQueryOrder() throws Exception {
    TestContext context = newContext(config(10, 1000L, 100), false);
    SessionDataSet escapeDataSet = aggDataSet();
    when(context.session().executeQueryStatement(anyString())).thenReturn(escapeDataSet);

    ReadTsKvQuery escaped = new BaseReadTsKvQuery("a'b", 1L, 10L, 5L, 10, Aggregation.SUM, "asc");
    context.dao().findAllAsync(TENANT_ID, ENTITY_ID, List.of(escaped)).get(3, TimeUnit.SECONDS);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000)).executeQueryStatement(sql.capture());
    assertTrue(sql.getValue().contains("key='a''b'"));

    // ThingsBoard ignores the query order for aggregation: an unrecognised order string is NOT
    // rejected (the aggregate is always emitted ascending), unlike the raw Aggregation.NONE path.
    ReadTsKvQuery ignoredOrder =
        new BaseReadTsKvQuery("k", 1L, 10L, 5L, 10, Aggregation.SUM, "sideways");
    context
        .dao()
        .findAllAsync(TENANT_ID, ENTITY_ID, List.of(ignoredOrder))
        .get(3, TimeUnit.SECONDS);
    ArgumentCaptor<String> ignoredOrderSql = ArgumentCaptor.forClass(String.class);
    verify(context.session(), timeout(3000).times(2))
        .executeQueryStatement(ignoredOrderSql.capture());
    String emitted = ignoredOrderSql.getAllValues().get(1);
    assertTrue(emitted.endsWith("GROUP BY 1 ORDER BY 1 ASC"), emitted);
    assertFalse(emitted.contains("sideways"), emitted);
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

  /** Unwraps the inner {@link KvEntry} so a test can assert the concrete data-entry type. */
  private KvEntry innerKv(TsKvEntry entry) {
    return assertInstanceOf(BasicTsKvEntry.class, entry).getKv();
  }

  private SessionDataSet aggDataSet(MockAggBucket... buckets)
      throws IoTDBConnectionException, StatementExecutionException {
    SessionDataSet dataSet = mock(SessionDataSet.class);
    SessionDataSet.DataIterator iterator = mock(SessionDataSet.DataIterator.class);
    AtomicInteger index = new AtomicInteger(-1);
    when(dataSet.iterator()).thenReturn(iterator);
    when(iterator.next()).thenAnswer(invocation -> index.incrementAndGet() < buckets.length);
    when(iterator.isNull(anyString()))
        .thenAnswer(invocation -> buckets[index.get()].isNull(invocation.getArgument(0)));
    // date_bin emits the startTs-anchored bucket START; the DAO derives the TB midpoint in Java.
    when(iterator.getTimestamp("bucket_ts"))
        .thenAnswer(invocation -> new Timestamp(buckets[index.get()].bucketStart()));
    // MAX(time) of the underlying data drives lastEntryTs; default to the bucket start.
    when(iterator.getTimestamp("max_ts"))
        .thenAnswer(invocation -> new Timestamp(buckets[index.get()].maxTs()));
    when(iterator.getDouble("agg_num")).thenAnswer(invocation -> buckets[index.get()].numeric());
    // sum_long is projected as SUM(CAST(long_v AS DOUBLE)) -- a DOUBLE -- so the DAO reads it via
    // getDouble.
    when(iterator.getDouble("sum_long")).thenAnswer(invocation -> buckets[index.get()].sumLong());
    when(iterator.getDouble("sum_double"))
        .thenAnswer(invocation -> buckets[index.get()].sumDouble());
    when(iterator.getString("agg_str")).thenAnswer(invocation -> buckets[index.get()].string());
    when(iterator.getLong(anyString()))
        .thenAnswer(invocation -> buckets[index.get()].longColumn(invocation.getArgument(0)));
    return dataSet;
  }

  /**
   * Models the raw {@code SELECT long_v ... AND long_v IS NOT NULL} re-query the SUM mapper issues
   * for a long-only bucket whose double-accumulated sum may have lost precision. Each supplied
   * value is one non-null {@code long_v} row; the DAO accumulates them in Java as {@code long}, so
   * the assertion proves the EXACT long sum (not the rounded double SUM).
   */
  private SessionDataSet rawLongDataSet(long... values)
      throws IoTDBConnectionException, StatementExecutionException {
    SessionDataSet dataSet = mock(SessionDataSet.class);
    SessionDataSet.DataIterator iterator = mock(SessionDataSet.DataIterator.class);
    AtomicInteger index = new AtomicInteger(-1);
    when(dataSet.iterator()).thenReturn(iterator);
    when(iterator.next()).thenAnswer(invocation -> index.incrementAndGet() < values.length);
    when(iterator.isNull("long_v")).thenReturn(false);
    when(iterator.getLong("long_v")).thenAnswer(invocation -> values[index.get()]);
    return dataSet;
  }

  private MockAggBucket numericBucket(long bucketStart, double numeric) {
    return MockAggBucket.numeric(bucketStart, bucketStart, numeric);
  }

  private MockAggBucket numericBucket(long bucketStart, long maxTs, double numeric) {
    return MockAggBucket.numeric(bucketStart, maxTs, numeric);
  }

  private MockAggBucket stringBucket(long bucketStart, String value) {
    return MockAggBucket.string(bucketStart, bucketStart, value);
  }

  /** A COUNT bucket whose value lands entirely in the long_v column (normal single-typed row). */
  private MockAggBucket countBucket(long bucketStart, long count) {
    return MockAggBucket.typedCount(bucketStart, bucketStart, 0L, 0L, 0L, count, 0L);
  }

  /**
   * The REAL row IoTDB returns for an EMPTY bounded calendar bucket: a single row whose typed COUNT
   * columns are all 0 and whose MAX(time)/aggregates are NULL (so {@code isNull("max_ts")} is
   * true). This is what the bounded per-bucket calendar path actually receives for an empty window,
   * unlike {@code aggDataSet()} (zero rows) which only the GROUP-BY milliseconds path produces.
   */
  private MockAggBucket emptyAggRow(long bucketStart) {
    return MockAggBucket.emptyAggRow(bucketStart);
  }

  private ReadTsKvQuery calendarQuery(
      String key,
      long startTs,
      long endTs,
      IntervalType intervalType,
      String tzId,
      Aggregation aggregation) {
    return new BaseReadTsKvQuery(
        key,
        startTs,
        endTs,
        AggregationParams.calendar(aggregation, intervalType, tzId),
        100,
        "ASC");
  }

  private ReadTsKvQuery calendarLikeMilliseconds(
      String key, long startTs, long endTs, long interval, Aggregation aggregation) {
    // Explicit MILLISECONDS IntervalType (with a tz set) must still route to the date_bin path.
    return new BaseReadTsKvQuery(
        key,
        startTs,
        endTs,
        AggregationParams.of(
            aggregation, IntervalType.MILLISECONDS, java.time.ZoneId.of("UTC"), interval),
        100,
        "DESC");
  }

  /**
   * Mocks one aggregation result row. {@code countBool/countStr/countJson/countLong/countDouble}
   * model ThingsBoard's per-type {@code SUM(CASE WHEN <col> IS NOT NULL THEN 1 ELSE 0 END)}
   * counters; the DAO selects the dominant one. {@code maxTs} models {@code MAX(time)} of the
   * underlying data for {@code lastEntryTs}. {@code emptyAgg} models the row IoTDB returns for an
   * empty bounded calendar bucket (one row whose {@code MAX(time)} is NULL and whose typed COUNT
   * columns are all 0); see {@link #emptyAggRow(long)}.
   */
  private record MockAggBucket(
      long bucketStart,
      long maxTs,
      Double numeric,
      String string,
      Long countBool,
      Long countStr,
      Long countJson,
      Long countLong,
      Long countDouble,
      Double sumLong,
      Double sumDouble,
      Long minLong,
      Long maxLong,
      boolean emptyAgg) {

    private static MockAggBucket numeric(long bucketStart, long maxTs, double numeric) {
      // AVG/MIN/MAX numeric bucket with no recorded long/double participation; the SUM and
      // MIN/MAX type-selection columns default to null (so isNull(...) is true), exercising the AVG
      // and string-fallback paths that do not depend on the typed counts.
      return new MockAggBucket(
          bucketStart,
          maxTs,
          numeric,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false);
    }

    /**
     * A MIN/MAX/AVG numeric bucket that also records the long/double participation counts, so the
     * result-type selector (long-only -> LONG, any double -> DOUBLE) can be exercised. {@code
     * countLong}/{@code countDouble} drive both the COUNT path and the MIN/MAX result type; {@code
     * numeric} is the MIN/MAX/AVG value read from {@code agg_num}. For a long-only bucket the
     * direct MIN(long_v)/MAX(long_v) channel is set to {@code (long) numeric} so the exact
     * long-channel MIN/MAX mapping reads the matching value; for a bucket with a participating
     * double the long channel is left null because the DOUBLE mapping ignores it.
     */
    private static MockAggBucket typedNumeric(
        long bucketStart, long maxTs, double numeric, long countLong, long countDouble) {
      Long longChannel = countDouble == 0L ? (long) numeric : null;
      return new MockAggBucket(
          bucketStart,
          maxTs,
          numeric,
          null,
          null,
          null,
          null,
          countLong,
          countDouble,
          null,
          null,
          longChannel,
          longChannel,
          false);
    }

    /**
     * A long-only MIN/MAX bucket that proves the EXACT long channel: {@code agg_num} holds the
     * value IoTDB's {@code COALESCE(double_v, CAST(long_v AS DOUBLE))} would produce (a double,
     * which silently rounds a long > 2^53), while {@code min_long}/{@code max_long} hold the EXACT
     * stored long. The DAO must read the long channel, so the entry's long value equals {@code
     * exactLong} (not the rounded {@code roundedAggNum}). A bucket is long-only (countDouble == 0).
     */
    private static MockAggBucket minMaxLong(
        long bucketStart, long maxTs, double roundedAggNum, long exactLong, long countLong) {
      return new MockAggBucket(
          bucketStart,
          maxTs,
          roundedAggNum,
          null,
          null,
          null,
          null,
          countLong,
          0L,
          null,
          null,
          exactLong,
          exactLong,
          false);
    }

    /**
     * A SUM bucket: the DAO reads {@code sum_long}/{@code sum_double} plus the long/double counts
     * (NOT {@code agg_num}). A long-only bucket records {@code countDouble == 0} and {@code
     * sumDouble == null}; a bucket with any double records {@code countDouble > 0} and a non-null
     * {@code sumDouble}. The {@code min_long}/{@code max_long} bound channels default to null (read
     * as 0), so a long-only bucket built this way always takes the exact-fast-path (maxAbs == 0
     * proves the double sum lost no bits); use {@link #sumWithBound} to drive the > 2^53 re-sum
     * fallback.
     */
    private static MockAggBucket sum(
        long bucketStart,
        long maxTs,
        Double sumLong,
        Double sumDouble,
        long countLong,
        long countDouble) {
      return new MockAggBucket(
          bucketStart,
          maxTs,
          null,
          null,
          null,
          null,
          null,
          countLong,
          countDouble,
          sumLong,
          sumDouble,
          null,
          null,
          false);
    }

    /**
     * A long-only SUM bucket that also records the {@code min_long}/{@code max_long} bound
     * channels. The DAO computes {@code maxAbs = max(|min_long|, |max_long|)} and trusts the
     * DOUBLE-accumulated {@code sum_long} only while {@code count_long * maxAbs <= 2^53}; otherwise
     * it re-queries the raw {@code long_v} values and sums them in Java. Used to drive both the
     * fast path (small bound) and the re-sum fallback (large bound).
     */
    private static MockAggBucket sumWithBound(
        long bucketStart, long maxTs, double sumLong, long countLong, long minLong, long maxLong) {
      return new MockAggBucket(
          bucketStart,
          maxTs,
          null,
          null,
          null,
          null,
          null,
          countLong,
          0L,
          sumLong,
          null,
          minLong,
          maxLong,
          false);
    }

    private static MockAggBucket string(long bucketStart, long maxTs, String value) {
      return new MockAggBucket(
          bucketStart,
          maxTs,
          null,
          value,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false);
    }

    private static MockAggBucket typedCount(
        long bucketStart,
        long maxTs,
        long countBool,
        long countStr,
        long countJson,
        long countLong,
        long countDouble) {
      return new MockAggBucket(
          bucketStart,
          maxTs,
          null,
          null,
          countBool,
          countStr,
          countJson,
          countLong,
          countDouble,
          null,
          null,
          null,
          null,
          false);
    }

    /**
     * Models the REAL row that IoTDB returns for an EMPTY bounded calendar bucket. Unlike {@code
     * aggDataSet()} with zero rows (which the GROUP-BY milliseconds path produces but the bounded
     * per-bucket calendar path never does), a single bounded aggregate over a window matching zero
     * rows still returns ONE row: every typed COUNT is 0 and every other aggregate (AVG/SUM/MIN/MAX
     * and MAX(time)) is NULL. The calendar reader's empty-bucket guard keys off {@code
     * isNull("max_ts")} (time is never null, so MAX(time) is NULL iff the window was empty), so
     * this row MUST report {@code isNull("max_ts") == true} while its typed-COUNT columns read 0.
     * Without the {@code !isNull(max_ts)} guard, COUNT's {@code LongDataEntry(0)} would leak as a
     * spurious empty bucket.
     */
    private static MockAggBucket emptyAggRow(long bucketStart) {
      return new MockAggBucket(
          bucketStart, bucketStart, null, null, 0L, 0L, 0L, 0L, 0L, null, null, null, null, true);
    }

    private long longColumn(String column) {
      return switch (column) {
        case "count_bool" -> orZero(countBool);
        case "count_str" -> orZero(countStr);
        case "count_json" -> orZero(countJson);
        case "count_long" -> orZero(countLong);
        case "count_double" -> orZero(countDouble);
        case "min_long" -> orZero(minLong);
        case "max_long" -> orZero(maxLong);
        default -> 0L;
      };
    }

    private static long orZero(Long value) {
      return value == null ? 0L : value;
    }

    private boolean isNull(String column) {
      return switch (column) {
        case "agg_num" -> numeric == null;
        case "agg_str" -> string == null;
        case "count_bool" -> countBool == null;
        case "count_str" -> countStr == null;
        case "count_json" -> countJson == null;
        case "count_long" -> countLong == null;
        case "count_double" -> countDouble == null;
        case "sum_long" -> sumLong == null;
        case "sum_double" -> sumDouble == null;
        case "min_long" -> minLong == null;
        case "max_long" -> maxLong == null;
          // MAX(time) is NULL iff the bounded window matched zero rows (time is never null). A real
          // empty calendar bucket (emptyAggRow) returns one row with MAX(time) NULL; every other
          // bucket has matching data, so its max_ts is non-null.
        case "max_ts" -> emptyAgg;
        default -> true;
      };
    }
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
