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

import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.StatementExecutionException;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for IoTDB Table Mode DAOs; not a Spring bean itself. Concrete DAOs declare
 * {@code @Repository} and the activation conditional. Holds the shared {@code ITableSessionPool}
 * (wired via constructor injection) and provides type-mapping helpers, SQL-literal escaping,
 * blank-key validation, entity predicates, and the shared bounded read/task executor used by
 * concrete DAOs (TimeseriesDao, LatestDao, AttributesDao, LabelDao).
 *
 * <p>The concrete DAOs opt into the bounded executor by calling {@link #initReadExecutor} from
 * their constructor and draining it via {@link #shutdownReadExecutor()} from {@code destroy()}; a
 * DAO that only maps rows (or a bare base instance) leaves the executor unset.
 *
 * <p><b>Shared read/write executor caveat.</b> A DAO that runs BOTH reads and writes through this
 * single bounded executor (the latest overlay does: {@code saveLatest}/{@code removeLatest} and the
 * dashboard {@code findLatest}/{@code findAllLatest} share it) can have a write burst that fills
 * the queue reject a concurrent read with an {@link IoTDBTableReadQueueFullException}. The
 * rejection message names this explicitly rather than splitting the executors; size the queue
 * capacity for the combined read + write load.
 */
@Slf4j
public class IoTDBTableBaseDao {
  protected final ITableSessionPool tableSessionPool;

  // Shared bounded read/task executor, initialized lazily by initReadExecutor() so a base instance
  // (or a read-only mapper DAO) that never calls it carries no executor. accepting/destroyed are
  // protected so a subclass can gate its own enqueue/shutdown fast-paths (e.g. save()).
  private ThreadPoolExecutor readExecutor;
  private final Set<ReadTask<?>> readTasks = ConcurrentHashMap.newKeySet();
  protected final AtomicBoolean accepting = new AtomicBoolean(true);
  protected final AtomicBoolean destroyed = new AtomicBoolean(false);
  private long shutdownDrainTimeoutMs;
  private String queueFullMessage;
  private String shuttingDownMessage;

  public IoTDBTableBaseDao(ITableSessionPool tableSessionPool) {
    this.tableSessionPool = tableSessionPool;
  }

  /**
   * Initializes the shared bounded read/task executor: a fixed-size {@link ThreadPoolExecutor}
   * backed by a bounded {@link ArrayBlockingQueue} with an {@link ThreadPoolExecutor.AbortPolicy}
   * rejection policy, so a saturated queue fails a submitted task fast rather than growing without
   * bound. Called by concrete DAOs from their constructor.
   *
   * @param threads fixed core/max worker count
   * @param queueCapacity bounded task-queue capacity
   * @param shutdownDrainTimeoutMs how long {@link #shutdownReadExecutor()} waits for in-flight
   *     tasks
   * @param threadNamePrefix worker thread-name prefix (a sequence number is appended)
   * @param queueFullMessage message for the {@link IoTDBTableReadQueueFullException} on rejection.
   *     For a DAO that shares this executor between reads and writes (the latest overlay), it
   *     should make clear that write-path saturation can reject a read
   * @param shuttingDownMessage message for the {@link IoTDBTableDaoShuttingDownException} raised
   *     once the DAO stops accepting work
   */
  protected void initReadExecutor(
      int threads,
      int queueCapacity,
      long shutdownDrainTimeoutMs,
      String threadNamePrefix,
      String queueFullMessage,
      String shuttingDownMessage) {
    this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
    this.queueFullMessage = Objects.requireNonNull(queueFullMessage, "queueFullMessage");
    this.shuttingDownMessage = Objects.requireNonNull(shuttingDownMessage, "shuttingDownMessage");
    this.readExecutor =
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            readThreadFactory(threadNamePrefix),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Submits a task to the bounded read executor and returns a {@link ListenableFuture} for its
   * result. Fails the future fast (never enqueues) once the DAO stops accepting work, and fails it
   * with an {@link IoTDBTableReadQueueFullException} when the bounded queue is full. Mirrors the
   * shutdown/queue-full races handled inline: a task the executor accepts but that races a
   * concurrent shutdown is removed and failed, so no submitted task is left hanging.
   */
  protected <T> ListenableFuture<T> submitReadTask(Callable<T> callable) {
    if (!accepting.get()) {
      return Futures.immediateFailedFuture(shuttingDownException());
    }
    ReadTask<T> task = new ReadTask<>(callable);
    readTasks.add(task);
    try {
      readExecutor.execute(task);
    } catch (RejectedExecutionException e) {
      if (!accepting.get() || readExecutor.isShutdown()) {
        task.fail(shuttingDownException());
      } else {
        task.fail(new IoTDBTableReadQueueFullException(queueFullMessage, e));
      }
      readTasks.remove(task);
      return task.future();
    }
    if (!accepting.get() && readExecutor.remove(task)) {
      task.fail(shuttingDownException());
      readTasks.remove(task);
    }
    return task.future();
  }

  /**
   * Stops accepting new work and drains the read executor: dropped queued tasks and any still
   * in-flight after the drain timeout are failed with the shutting-down exception. Idempotent guard
   * ({@link #destroyed}) is the caller's responsibility (each DAO's {@code destroy()} runs it
   * once).
   */
  protected void shutdownReadExecutor() {
    accepting.set(false);
    IoTDBTableDaoShuttingDownException failure = shuttingDownException();
    for (Runnable dropped : readExecutor.shutdownNow()) {
      failDroppedReadTask(dropped, failure);
    }
    try {
      readExecutor.awaitTermination(shutdownDrainTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    for (ReadTask<?> task : readTasks) {
      task.fail(failure);
    }
  }

  private void failDroppedReadTask(Runnable dropped, IoTDBTableDaoShuttingDownException failure) {
    if (dropped instanceof ReadTask<?> task) {
      task.fail(failure);
      readTasks.remove(task);
    }
  }

  protected IoTDBTableDaoShuttingDownException shuttingDownException() {
    return new IoTDBTableDaoShuttingDownException(shuttingDownMessage);
  }

  private static ThreadFactory readThreadFactory(String threadNamePrefix) {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, threadNamePrefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Single-quote SQL string literal. This relies on the IoTDB Table-Mode STRING-literal grammar
   * (RelationalSql.g4): the single quote is the only special character and is escaped by doubling
   * it (''), with NO backslash escaping. If a future IoTDB lexer adds backslash/other escapes, this
   * doubling alone would no longer be sufficient and must be revisited.
   */
  protected static String sqlString(String value) {
    return "'" + Objects.requireNonNull(value, "value").replace("'", "''") + "'";
  }

  /** Rejects a null/blank telemetry key, mirroring each DAO's fail-fast contract. */
  protected static String requireTelemetryKey(String key) {
    return requireKey(key, "Telemetry");
  }

  /**
   * Rejects a null/blank key, naming the subject ({@code "Telemetry"} / {@code "Attribute"}) in the
   * message so each DAO keeps its own fail-fast wording.
   */
  protected static String requireKey(String key, String subject) {
    if (key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException(subject + " key must not be blank");
    }
    return key;
  }

  /** Full-identity WHERE fragment shared by the SQL builders (tenant + entity type + entity id). */
  protected String entityPredicate(TenantId tenantId, EntityId entityId) {
    return "tenant_id="
        + sqlString(tenantId.getId().toString())
        + " AND entity_type="
        + sqlString(entityId.getEntityType().name())
        + " AND entity_id="
        + sqlString(entityId.getId().toString());
  }

  /** Maps a resolved single-typed telemetry value to the matching ThingsBoard {@link KvEntry}. */
  protected KvEntry kvEntry(String key, TypedKvValue value) {
    if (value.booleanValue() != null) {
      return new BooleanDataEntry(key, value.booleanValue());
    }
    if (value.longValue() != null) {
      return new LongDataEntry(key, value.longValue());
    }
    if (value.doubleValue() != null) {
      return new DoubleDataEntry(key, value.doubleValue());
    }
    if (value.stringValue() != null) {
      return new StringDataEntry(key, value.stringValue());
    }
    if (value.jsonValue() != null) {
      return new JsonDataEntry(key, value.jsonValue());
    }
    throw new IllegalArgumentException("Row does not contain a typed value");
  }

  /**
   * Maps a single IoTDB Table Mode telemetry row's 5 typed FIELD columns to a TypedKvValue. Exactly
   * one typed value column may be non-null because the telemetry schema stores exactly one typed
   * value per row. Throws IllegalStateException if more than one is non-null (fail-fast on schema
   * invariant violation). Returns TypedKvValue.empty() if all 5 are null (caller decides how to
   * handle). The caller must call row.next() and receive true before invoking this method so the
   * iterator is positioned on a row.
   */
  public TypedKvValue getEntry(SessionDataSet.DataIterator row) throws StatementExecutionException {
    boolean hasBoolean = !row.isNull("bool_v");
    boolean hasLong = !row.isNull("long_v");
    boolean hasDouble = !row.isNull("double_v");
    boolean hasString = !row.isNull("str_v");
    boolean hasJson = !row.isNull("json_v");

    int valueCount = 0;
    valueCount += hasBoolean ? 1 : 0;
    valueCount += hasLong ? 1 : 0;
    valueCount += hasDouble ? 1 : 0;
    valueCount += hasString ? 1 : 0;
    valueCount += hasJson ? 1 : 0;

    if (valueCount == 0) {
      return TypedKvValue.empty();
    }
    if (valueCount > 1) {
      throw new IllegalStateException(
          "IoTDB telemetry row has "
              + valueCount
              + " typed value columns set; the telemetry schema stores exactly one typed value "
              + "column per row. "
              + "Possible same-timestamp type-change bug or stale data.");
    }
    if (hasBoolean) {
      return TypedKvValue.ofBoolean(row.getBoolean("bool_v"));
    }
    if (hasLong) {
      return TypedKvValue.ofLong(row.getLong("long_v"));
    }
    if (hasDouble) {
      return TypedKvValue.ofDouble(row.getDouble("double_v"));
    }
    if (hasString) {
      return TypedKvValue.ofString(row.getString("str_v"));
    }
    return TypedKvValue.ofJson(row.getString("json_v"));
  }

  private final class ReadTask<T> implements Runnable {
    private final Callable<T> callable;
    private final SettableFuture<T> future = SettableFuture.create();

    private ReadTask(Callable<T> callable) {
      this.callable = Objects.requireNonNull(callable, "callable");
    }

    @Override
    public void run() {
      try {
        future.set(callable.call());
      } catch (Throwable t) {
        future.setException(t);
      } finally {
        readTasks.remove(this);
      }
    }

    private ListenableFuture<T> future() {
      return future;
    }

    private void fail(Throwable t) {
      future.setException(t);
    }
  }
}
