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
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.rpc.TSStatusCode;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;
import org.springframework.beans.factory.DisposableBean;
import org.thingsboard.server.common.data.kv.DataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Slf4j
public class IoTDBTableTimeseriesWriter implements DisposableBean {
  static final String TABLE_NAME = "telemetry";
  static final List<String> COLUMN_NAMES =
      List.of(
          "entity_type",
          "tenant_id",
          "key",
          "entity_id",
          "bool_v",
          "long_v",
          "double_v",
          "str_v",
          "json_v");
  static final List<TSDataType> DATA_TYPES =
      List.of(
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.BOOLEAN,
          TSDataType.INT64,
          TSDataType.DOUBLE,
          TSDataType.STRING,
          TSDataType.TEXT);
  static final List<ColumnCategory> COLUMN_CATEGORIES =
      List.of(
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD);
  // Rate-limited because overload or shutdown can reject many points on the write hot path.
  private static final long REJECT_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final long NEVER_WARNED_NANOS = Long.MIN_VALUE;
  // After the graceful drain window expires, wait briefly for the interrupted worker to stop.
  private static final long FORCE_STOP_JOIN_TIMEOUT_MS = 1000L;

  private final ITableSessionPool tableSessionPool;
  private final BlockingQueue<IoTDBTablePendingSave> queue;
  private final LongSupplier nanoTime;
  private final int batchSize;
  private final long maxLingerNanos;
  private final long shutdownDrainTimeoutMs;
  private final int retryMaxAttempts;
  private final long retryInitialBackoffMs;
  private final long retryMaxBackoffMs;
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean destroyed = new AtomicBoolean(false);
  private final ConcurrentMap<SettableFuture<Integer>, IoTDBTablePendingSave> acceptedSaves =
      new ConcurrentHashMap<>();
  private final AtomicLong enqueued = new AtomicLong();
  private final AtomicLong flushed = new AtomicLong();
  private final AtomicLong flushFailures = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();
  private final AtomicLong rejectsFull = new AtomicLong();
  private final AtomicLong rejectsShutdown = new AtomicLong();
  private final AtomicLong lastRejectWarnNanos = new AtomicLong(NEVER_WARNED_NANOS);
  private final AtomicLong shutdownFailedPending = new AtomicLong();
  private final AtomicLong queueDepth = new AtomicLong();
  private volatile boolean forceStopped;
  private final Thread worker;

  public IoTDBTableTimeseriesWriter(ITableSessionPool tableSessionPool, IoTDBTableConfig config) {
    this(tableSessionPool, config, true);
  }

  IoTDBTableTimeseriesWriter(
      ITableSessionPool tableSessionPool, IoTDBTableConfig config, boolean startWorker) {
    this(
        tableSessionPool,
        config,
        startWorker,
        new ArrayBlockingQueue<>(config.getTs().getSave().getQueueCapacity()),
        System::nanoTime);
  }

  IoTDBTableTimeseriesWriter(
      ITableSessionPool tableSessionPool,
      IoTDBTableConfig config,
      boolean startWorker,
      BlockingQueue<IoTDBTablePendingSave> queue) {
    this(tableSessionPool, config, startWorker, queue, System::nanoTime);
  }

  IoTDBTableTimeseriesWriter(
      ITableSessionPool tableSessionPool,
      IoTDBTableConfig config,
      boolean startWorker,
      BlockingQueue<IoTDBTablePendingSave> queue,
      LongSupplier nanoTime) {
    this.tableSessionPool = Objects.requireNonNull(tableSessionPool, "tableSessionPool");
    Objects.requireNonNull(config, "config");
    IoTDBTableConfig.Save saveConfig = config.getTs().getSave();
    this.batchSize = saveConfig.getBatchSize();
    this.maxLingerNanos = TimeUnit.MILLISECONDS.toNanos(saveConfig.getMaxLingerMs());
    this.shutdownDrainTimeoutMs = saveConfig.getShutdownDrainTimeoutMs();
    this.retryMaxAttempts = saveConfig.getRetryMaxAttempts();
    this.retryInitialBackoffMs = saveConfig.getRetryInitialBackoffMs();
    this.retryMaxBackoffMs = saveConfig.getRetryMaxBackoffMs();
    this.queue = Objects.requireNonNull(queue, "queue");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.worker = new Thread(this::runFlushLoop, "iotdb-table-timeseries-flush-worker");
    this.worker.setDaemon(true);
    if (startWorker) {
      this.worker.start();
    }
  }

  public ListenableFuture<Integer> enqueue(IoTDBTablePendingSave pending) {
    Objects.requireNonNull(pending, "pending");
    if (!accepting.get()) {
      rejectsShutdown.incrementAndGet();
      warnRejectedSave("shutting-down");
      pending.future().setException(shuttingDownException());
      return pending.future();
    }
    if (!queue.offer(pending)) {
      rejectsFull.incrementAndGet();
      warnRejectedSave("queue-full");
      pending
          .future()
          .setException(
              new IoTDBTableSaveQueueFullException(
                  "IoTDB Table Mode timeseries save queue is full"));
      return pending.future();
    }
    registerAccepted(pending);
    enqueued.incrementAndGet();
    queueDepth.incrementAndGet();
    if (!accepting.get() && queue.remove(pending)) {
      queueDepth.decrementAndGet();
      rejectsShutdown.incrementAndGet();
      warnRejectedSave("shutting-down");
      pending.future().setException(shuttingDownException());
      unregisterAccepted(pending);
    } else if (pending.future().isDone()) {
      unregisterAccepted(pending);
    }
    return pending.future();
  }

  public IoTDBTableTimeseriesWriterStats stats() {
    return new IoTDBTableTimeseriesWriterStats(
        enqueued.get(),
        flushed.get(),
        flushFailures.get(),
        retries.get(),
        rejectsFull.get(),
        rejectsShutdown.get(),
        shutdownFailedPending.get(),
        queueDepth.get());
  }

  Thread workerThread() {
    return worker;
  }

  /**
   * Stops accepting saves and gives the flush worker a bounded drain window.
   *
   * <p>If that window expires, shutdown enters forced-stop mode: the worker is interrupted, local
   * batches that were already dequeued are failed, and every accepted future is completed or failed
   * before this method returns. If the timeout races an insert that has already started, the write
   * may still commit after its future is failed. Shutdown therefore remains at least once: callers
   * must tolerate duplicate or uncertain final-batch writes.
   */
  @Override
  public void destroy() {
    if (!destroyed.compareAndSet(false, true)) {
      return;
    }
    accepting.set(false);
    // Do NOT interrupt the worker up front. An in-flight retry backoff (Thread.sleep in
    // sleepBeforeRetry) would throw InterruptedException, flushBatch's catch would fail the
    // already-accepted batch, and the drain window would be wasted on exactly the
    // transient-error-during-shutdown path it exists to protect. With accepting=false the worker
    // observes shutdown within one poll cycle (<=100ms), lets any in-flight retry finish, and
    // drains
    // the queue + current batch on its own. Only if it is STILL alive after the drain timeout
    // (below)
    // do we interrupt and fail whatever remains.
    try {
      worker.join(shutdownDrainTimeoutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) {
      IoTDBTableDaoShuttingDownException failure =
          new IoTDBTableDaoShuttingDownException(
              "IoTDB Table Mode timeseries DAO shutdown drain timed out");
      forceStopped = true;
      failUnfinishedPending(failure);
      worker.interrupt();
      try {
        worker.join(FORCE_STOP_JOIN_TIMEOUT_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      failUnfinishedPending(failure);
    } else if (!queue.isEmpty()) {
      failUnfinishedPending(
          new IoTDBTableDaoShuttingDownException(
              "IoTDB Table Mode timeseries DAO stopped before draining pending writes"));
    }
  }

  private void runFlushLoop() {
    List<IoTDBTablePendingSave> batch = new ArrayList<>(batchSize);
    while (!forceStopped && (accepting.get() || !queue.isEmpty() || !batch.isEmpty())) {
      try {
        if (batch.isEmpty()) {
          IoTDBTablePendingSave first = queue.poll(100L, TimeUnit.MILLISECONDS);
          if (first == null) {
            continue;
          }
          addToBatch(batch, first);
        }
        fillBatchUntilReady(batch);
        if (!batch.isEmpty()) {
          flushBatch(batch);
          batch.clear();
        }
      } catch (InterruptedException e) {
        if (accepting.get()) {
          Thread.currentThread().interrupt();
          failBatch(
              batch,
              new IoTDBTableDaoShuttingDownException(
                  "IoTDB Table Mode timeseries flush worker was interrupted"));
          batch.clear();
          break;
        }
        if (forceStopped) {
          failBatch(batch, forcedStopException());
          batch.clear();
          break;
        }
      } catch (RuntimeException e) {
        failBatch(batch, e);
        batch.clear();
      } catch (Throwable t) {
        failBatch(batch, t);
        batch.clear();
      }
    }
    if (forceStopped && !batch.isEmpty()) {
      failBatch(batch, forcedStopException());
      batch.clear();
    }
  }

  // Upper bound on each linger-poll slice, so an in-flight linger wait observes a concurrent
  // shutdown
  // (accepting=false) within one slice instead of waiting out a large maxLingerMs -- WITHOUT
  // interrupting the worker (an interrupt would abort an in-flight retry backoff; see destroy()).
  private static final long SHUTDOWN_OBSERVE_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

  private void fillBatchUntilReady(List<IoTDBTablePendingSave> batch) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + maxLingerNanos;
    while (batch.size() < batchSize) {
      // Once shutdown has begun, stop lingering and flush the partial batch immediately: any items
      // still queued are drained by the outer runFlushLoop (its loop condition includes
      // !queue.isEmpty()), so no accepted write is lost and the drain window is not spent waiting
      // out maxLingerMs. The accepted-saves registry lets a forced stop fail the local batch even
      // before flushBatch begins.
      if (forceStopped || !accepting.get()) {
        return;
      }
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0L) {
        return;
      }
      // Poll in bounded slices so a shutdown that races an in-flight linger wait is observed within
      // one slice. With the default small maxLingerMs the slice covers the whole wait (single poll,
      // unchanged behaviour); only a large maxLingerMs is sliced.
      long sliceNanos = Math.min(remainingNanos, SHUTDOWN_OBSERVE_SLICE_NANOS);
      IoTDBTablePendingSave next = queue.poll(sliceNanos, TimeUnit.NANOSECONDS);
      if (next == null) {
        // Slice elapsed with no item: re-check accepting + the maxLinger deadline at the loop top.
        continue;
      }
      addToBatch(batch, next);
      drainAvailable(batch);
    }
  }

  private void drainAvailable(List<IoTDBTablePendingSave> batch) {
    int limit = batchSize - batch.size();
    if (limit <= 0) {
      return;
    }
    List<IoTDBTablePendingSave> drained = new ArrayList<>(limit);
    int drainedCount = queue.drainTo(drained, limit);
    if (drainedCount == 0) {
      return;
    }
    queueDepth.addAndGet(-drainedCount);
    batch.addAll(drained);
  }

  private void addToBatch(List<IoTDBTablePendingSave> batch, IoTDBTablePendingSave pending) {
    queueDepth.decrementAndGet();
    batch.add(pending);
  }

  private void flushBatch(List<IoTDBTablePendingSave> rawBatch) {
    if (forceStopped) {
      failBatch(rawBatch, forcedStopException());
      return;
    }
    List<IoTDBTablePendingSave> insertBatch = deduplicateForInsert(rawBatch);
    if (insertBatch.isEmpty()) {
      return;
    }
    try {
      Tablet tablet = buildTablet(insertBatch);
      insertWithRetry(tablet);
      flushed.addAndGet(insertBatch.size());
      completeBatch(rawBatch);
    } catch (InterruptedException e) {
      flushFailures.incrementAndGet();
      if (forceStopped) {
        failBatch(rawBatch, forcedStopException());
      } else {
        Thread.currentThread().interrupt();
        failBatch(rawBatch, e);
      }
    } catch (Throwable t) {
      flushFailures.incrementAndGet();
      failBatch(rawBatch, t);
    }
  }

  private List<IoTDBTablePendingSave> deduplicateForInsert(List<IoTDBTablePendingSave> rawBatch) {
    // Collapses duplicate (tenant, entity, key, time) saves within a single flush so the tablet
    // honors the design's same-(tags, time) overwrite contract: the last write wins. Cross-flush
    // same-time type changes are out of scope for this iteration -- a delete-then-insert defense is
    // outside the current scope (see the module README "Known limitations"). The read path fails
    // fast on a row that ends up with two typed columns, so this relaxation never silently returns
    // wrong data.
    Map<IoTDBTableSaveIdentity, IoTDBTablePendingSave> lastByIdentity =
        new LinkedHashMap<>(rawBatch.size());
    for (IoTDBTablePendingSave pending : rawBatch) {
      IoTDBTableSaveIdentity identity = pending.identity();
      lastByIdentity.remove(identity);
      lastByIdentity.put(identity, pending);
    }
    return new ArrayList<>(lastByIdentity.values());
  }

  Tablet buildTablet(List<IoTDBTablePendingSave> batch) {
    Tablet tablet =
        new Tablet(TABLE_NAME, COLUMN_NAMES, DATA_TYPES, COLUMN_CATEGORIES, batch.size());
    for (int row = 0; row < batch.size(); row++) {
      IoTDBTablePendingSave pending = batch.get(row);
      tablet.addTimestamp(row, pending.ts());
      tablet.addValue("entity_type", row, pending.entityType());
      tablet.addValue("tenant_id", row, pending.tenantId());
      tablet.addValue("key", row, pending.key());
      tablet.addValue("entity_id", row, pending.entityId());
      tablet.addValue(
          "bool_v", row, pending.dataType() == DataType.BOOLEAN ? pending.value() : null);
      tablet.addValue("long_v", row, pending.dataType() == DataType.LONG ? pending.value() : null);
      tablet.addValue(
          "double_v", row, pending.dataType() == DataType.DOUBLE ? pending.value() : null);
      tablet.addValue("str_v", row, pending.dataType() == DataType.STRING ? pending.value() : null);
      tablet.addValue("json_v", row, pending.dataType() == DataType.JSON ? pending.value() : null);
    }
    tablet.setRowSize(batch.size());
    return tablet;
  }

  private void insertWithRetry(Tablet tablet)
      throws IoTDBConnectionException, StatementExecutionException, InterruptedException {
    long backoffMs = initialBackoffMs(retryInitialBackoffMs, retryMaxBackoffMs);
    for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
      if (forceStopped) {
        throw forcedStopException();
      }
      boolean inserted = false;
      try (ITableSession session = tableSessionPool.getSession()) {
        if (forceStopped) {
          throw forcedStopException();
        }
        session.insert(tablet);
        inserted = true;
        return;
      } catch (IoTDBConnectionException e) {
        if (inserted) {
          // close() failed after insert returned; do not replay a tablet that may be persisted.
          return;
        }
        if (attempt >= retryMaxAttempts) {
          throw e;
        }
        retries.incrementAndGet();
        sleepBeforeRetry(backoffMs);
        backoffMs = nextBackoffMs(backoffMs);
      } catch (StatementExecutionException e) {
        // No `inserted` guard here (unlike the IoTDBConnectionException path): insert(tablet) is
        // the
        // only statement in the try block that can raise StatementExecutionException — session
        // acquisition and close() raise only IoTDBConnectionException — so reaching this catch
        // always
        // means the insert itself failed and nothing was persisted.
        // Only transient server-side conditions (overload / timeout / dispatch / retryable) are
        // worth retrying; semantic failures (parse / type / schema) are permanent, so fail fast
        // instead of burning the whole retry budget on a request that can never succeed.
        if (!isTransient(e.getStatusCode()) || attempt >= retryMaxAttempts) {
          throw e;
        }
        retries.incrementAndGet();
        sleepBeforeRetry(backoffMs);
        backoffMs = nextBackoffMs(backoffMs);
      }
    }
    throw new IllegalStateException("IoTDB insert retry loop exited without success or failure");
  }

  /**
   * Transient IoTDB server-side conditions that justify retrying the same batch. Everything else
   * (parse / semantic / type / schema errors) is treated as permanent and fails fast. Status codes
   * are referenced via {@link TSStatusCode} so the mapping survives server error-code churn.
   */
  private static boolean isTransient(int statusCode) {
    return statusCode == TSStatusCode.WRITE_PROCESS_REJECT.getStatusCode()
        || statusCode == TSStatusCode.INTERNAL_REQUEST_TIME_OUT.getStatusCode()
        || statusCode == TSStatusCode.INTERNAL_REQUEST_RETRY_ERROR.getStatusCode()
        || statusCode == TSStatusCode.TOO_MANY_CONCURRENT_QUERIES_ERROR.getStatusCode()
        || statusCode == TSStatusCode.DISPATCH_ERROR.getStatusCode();
  }

  static long initialBackoffMs(long initialBackoffMs, long maxBackoffMs) {
    if (maxBackoffMs <= 0L) {
      return initialBackoffMs;
    }
    return Math.min(initialBackoffMs, maxBackoffMs);
  }

  private IoTDBTableDaoShuttingDownException shuttingDownException() {
    return new IoTDBTableDaoShuttingDownException(
        "IoTDB Table Mode timeseries DAO is shutting down");
  }

  private IoTDBTableDaoShuttingDownException forcedStopException() {
    return new IoTDBTableDaoShuttingDownException(
        "IoTDB Table Mode timeseries DAO shutdown drain timed out");
  }

  private void warnRejectedSave(String reason) {
    if (!shouldLogRejectedSaveWarning()) {
      return;
    }
    log.warn(
        "IoTDB Table Mode timeseries save rejected (reason={}, rejectsFull={}, rejectsShutdown={})",
        reason,
        rejectsFull.get(),
        rejectsShutdown.get());
  }

  boolean shouldLogRejectedSaveWarning() {
    long nowNanos = nanoTime.getAsLong();
    long previousNanos = lastRejectWarnNanos.get();
    if (previousNanos != NEVER_WARNED_NANOS
        && nowNanos - previousNanos < REJECT_WARN_INTERVAL_NANOS) {
      return false;
    }
    return lastRejectWarnNanos.compareAndSet(previousNanos, nowNanos);
  }

  private void sleepBeforeRetry(long backoffMs) throws InterruptedException {
    if (backoffMs > 0L) {
      Thread.sleep(backoffMs);
    }
  }

  private long nextBackoffMs(long currentBackoffMs) {
    if (retryMaxBackoffMs <= 0L) {
      return 0L;
    }
    if (currentBackoffMs <= 0L) {
      return Math.min(1L, retryMaxBackoffMs);
    }
    long doubled = currentBackoffMs > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : currentBackoffMs * 2L;
    return Math.min(retryMaxBackoffMs, doubled);
  }

  private void completeBatch(List<IoTDBTablePendingSave> batch) {
    for (IoTDBTablePendingSave pending : batch) {
      pending.future().set(pending.dataPointDays());
      unregisterAccepted(pending);
    }
  }

  private void failBatch(List<IoTDBTablePendingSave> batch, Throwable t) {
    for (IoTDBTablePendingSave pending : batch) {
      pending.future().setException(t);
      unregisterAccepted(pending);
    }
  }

  private void registerAccepted(IoTDBTablePendingSave pending) {
    acceptedSaves.put(pending.future(), pending);
  }

  private void unregisterAccepted(IoTDBTablePendingSave pending) {
    acceptedSaves.remove(pending.future(), pending);
  }

  private void failUnfinishedPending(RuntimeException failure) {
    List<IoTDBTablePendingSave> pending = new ArrayList<>();
    queue.drainTo(pending);
    queueDepth.addAndGet(-pending.size());
    for (IoTDBTablePendingSave save : pending) {
      acceptedSaves.putIfAbsent(save.future(), save);
    }
    long failed = 0L;
    for (Map.Entry<SettableFuture<Integer>, IoTDBTablePendingSave> entry :
        acceptedSaves.entrySet()) {
      if (entry.getKey().setException(failure)) {
        failed++;
      }
      acceptedSaves.remove(entry.getKey(), entry.getValue());
    }
    shutdownFailedPending.addAndGet(failed);
  }
}
