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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Repository;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
 * Historical telemetry DAO for the IoTDB Table Mode backend.
 *
 * <p>Spring activation: database.ts.type=iotdb-table and iotdb.ts.experimental-raw-only=true.
 *
 * <p>Strategy F consumes ThingsBoard common-data types from the compile classpath and binds the
 * real historical {@link TimeseriesDao} SPI.
 *
 * <p>This initial implementation delivers the batch WRITE path ({@link #save}), the RAW
 * (non-aggregated) historical READ path ({@link #findAllAsync}) and the DELETE path ({@link
 * #remove}), all driven through a bounded read thread-pool. The time-bucketed aggregation read path
 * is not implemented; a positive-interval aggregation query still throws {@link
 * UnsupportedOperationException}.
 */
@Slf4j
@Repository
@ConditionalOnBean(name = IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
@Conditional(IoTDBTableRawOnlyEnabledCondition.class)
public class IoTDBTableTimeseriesDao extends IoTDBTableBaseDao
    implements TimeseriesDao, DisposableBean {
  private static final long SECONDS_PER_DAY = 86400L;
  private static final String TABLE_NAME = IoTDBTableTimeseriesWriter.TABLE_NAME;

  private final IoTDBTableTimeseriesWriter timeseriesWriter;
  private final ThreadPoolExecutor readExecutor;
  private final Set<ReadTask<?>> readTasks = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean destroyed = new AtomicBoolean(false);
  private final long defaultTtlSeconds;
  private final long shutdownDrainTimeoutMs;

  public IoTDBTableTimeseriesDao(
      @Qualifier(IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
          ITableSessionPool tableSessionPool,
      IoTDBTableTimeseriesWriter timeseriesWriter,
      IoTDBTableConfig config) {
    super(tableSessionPool);
    this.timeseriesWriter = Objects.requireNonNull(timeseriesWriter, "timeseriesWriter");
    this.defaultTtlSeconds =
        config.getDefaultTtlMs() > 0L
            ? TimeUnit.MILLISECONDS.toSeconds(config.getDefaultTtlMs())
            : 0L;
    this.shutdownDrainTimeoutMs = config.getTs().getSave().getShutdownDrainTimeoutMs();
    int readThreads = config.getTs().getRead().getThreads();
    int readQueueCapacity = config.getTs().getRead().getQueueCapacity();
    int flushThreads = config.getTs().getSave().getFlushThreads();
    if (readThreads + flushThreads > config.getSessionPoolSize()) {
      log.warn(
          "IoTDB Table Mode read/write workers ({}) exceed session pool size ({}); "
              + "reads or flushes may wait for sessions",
          readThreads + flushThreads,
          config.getSessionPoolSize());
    }
    this.readExecutor =
        new ThreadPoolExecutor(
            readThreads,
            readThreads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(readQueueCapacity),
            readThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Override
  public ListenableFuture<List<ReadTsKvQueryResult>> findAllAsync(
      TenantId tenantId, EntityId entityId, List<ReadTsKvQuery> queries) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(queries, "queries");
    if (!accepting.get()) {
      return Futures.immediateFailedFuture(shuttingDownException());
    }

    // Reject blank telemetry keys before any read task is enqueued, mirroring save()'s fail-fast
    // contract, so an invalid query never occupies a read-pool slot.
    try {
      for (ReadTsKvQuery query : queries) {
        Objects.requireNonNull(query, "query");
        requireTelemetryKey(query.getKey());
      }
    } catch (RuntimeException e) {
      return Futures.immediateFailedFuture(e);
    }

    List<ListenableFuture<ReadTsKvQueryResult>> futures = new ArrayList<>(queries.size());
    for (ReadTsKvQuery query : queries) {
      futures.add(submitReadTask(() -> readQuery(tenantId, entityId, query)));
    }
    return Futures.allAsList(futures);
  }

  @Override
  public ListenableFuture<Integer> save(
      TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, long ttl) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(tsKvEntry, "tsKvEntry");

    try {
      String key = requireTelemetryKey(tsKvEntry.getKey());
      // Mirror the read/delete shutdown-race guard: once the DAO has stopped accepting work, fail
      // fast instead of enqueueing into a writer that is (or is about to be) draining/destroyed.
      if (!accepting.get()) {
        return Futures.immediateFailedFuture(shuttingDownException());
      }
      return timeseriesWriter.enqueue(
          new IoTDBTablePendingSave(
              tenantId.getId().toString(),
              entityId.getEntityType().name(),
              entityId.getId().toString(),
              key,
              tsKvEntry.getTs(),
              tsKvEntry.getDataType(),
              typedValue(tsKvEntry),
              dataPointDays(tsKvEntry, ttl)));
    } catch (RuntimeException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  @Override
  public ListenableFuture<Integer> savePartition(
      TenantId tenantId, EntityId entityId, long ts, String key) {
    // IoTDB Table Mode has no per-partition bookkeeping; the write path is partition-agnostic, so
    // there is nothing to persist for a partition marker. Matches the contract ThingsBoard expects
    // from a DAO that does not maintain a partitions table.
    return Futures.immediateFuture(0);
  }

  @Override
  public ListenableFuture<Void> remove(
      TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(query, "query");

    // Reject blank telemetry keys before enqueueing the delete task, mirroring save()'s fail-fast
    // contract, so an invalid delete never occupies a read-pool slot.
    try {
      requireTelemetryKey(query.getKey());
    } catch (RuntimeException e) {
      return Futures.immediateFailedFuture(e);
    }

    return submitReadTask(
        () -> {
          String sql = buildDeleteSql(tenantId, entityId, query);
          try (ITableSession session = tableSessionPool.getSession()) {
            session.executeNonQueryStatement(sql);
          }
          return null;
        });
  }

  @Override
  public void cleanup(long systemTtl) {
    // No-op: physical retention is a table-level IoTDB property (TTL in ms), owned by the
    // operator's schema (WITH (TTL=<ms>) or ALTER TABLE telemetry SET PROPERTIES TTL=<ms>), not
    // driven from this per-call hook. IoTDB Table Mode TTL cannot honor a per-data-point ttl, so
    // the module does not issue retention DDL here.
  }

  public IoTDBTableTimeseriesWriterStats stats() {
    return timeseriesWriter.stats();
  }

  @Override
  public void destroy() {
    if (!destroyed.compareAndSet(false, true)) {
      return;
    }
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
    timeseriesWriter.destroy();
  }

  private ReadTsKvQueryResult readQuery(TenantId tenantId, EntityId entityId, ReadTsKvQuery query)
      throws Exception {
    // ThingsBoard 4.3.1.2 (AbstractChunkedAggregationTimeseriesDao.findAllAsync) routes a query to
    // the
    // RAW findAllWithLimit path when aggregation == NONE OR interval < 1 -- a sub-1 interval is a
    // valid
    // TB query shape that returns raw telemetry, not an error. Only a positive-interval aggregation
    // enters the bucketed path.
    if (aggregationOf(query) == Aggregation.NONE || query.getInterval() < 1L) {
      return readRawQuery(tenantId, entityId, query);
    }
    // The positive-interval, time-bucketed aggregation read path is not implemented; only the RAW
    // (Aggregation.NONE or interval < 1) branch is implemented now.
    throw new UnsupportedOperationException(
        "Time-bucketed aggregation is not supported by this incremental IoTDB Table Mode backend"
            + " yet; raw read, write and delete are available.");
  }

  private ReadTsKvQueryResult readRawQuery(
      TenantId tenantId, EntityId entityId, ReadTsKvQuery query) throws Exception {
    String key = requireTelemetryKey(query.getKey());
    String order = sqlOrder(query.getOrder());
    if (query.getLimit() <= 0) {
      return new ReadTsKvQueryResult(query.getId(), List.of(), query.getStartTs());
    }

    String sql = buildReadSql(tenantId, entityId, query, order);
    List<TsKvEntry> entries = new ArrayList<>();
    long lastEntryTs = query.getStartTs();
    boolean hasEntry = false;
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        TypedKvValue value = getEntry(row);
        if (!value.hasValue()) {
          continue;
        }
        long ts = row.getTimestamp("time").getTime();
        entries.add(new BasicTsKvEntry(ts, kvEntry(key, value)));
        if (!hasEntry || ts > lastEntryTs) {
          lastEntryTs = ts;
          hasEntry = true;
        }
      }
    }
    return new ReadTsKvQueryResult(query.getId(), entries, lastEntryTs);
  }

  private String buildReadSql(
      TenantId tenantId, EntityId entityId, ReadTsKvQuery query, String order) {
    String key = requireTelemetryKey(query.getKey());
    return "SELECT time, bool_v, long_v, double_v, str_v, json_v FROM "
        + TABLE_NAME
        + " WHERE tenant_id="
        + sqlString(tenantId.getId().toString())
        + " AND entity_type="
        + sqlString(entityId.getEntityType().name())
        + " AND entity_id="
        + sqlString(entityId.getId().toString())
        + " AND key="
        + sqlString(key)
        + " AND time >= "
        + query.getStartTs()
        + " AND time < "
        + query.getEndTs()
        + " ORDER BY time "
        + order
        + " LIMIT "
        + query.getLimit();
  }

  private String buildDeleteSql(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
    String key = requireTelemetryKey(query.getKey());
    return "DELETE FROM "
        + TABLE_NAME
        + " WHERE tenant_id="
        + sqlString(tenantId.getId().toString())
        + " AND entity_type="
        + sqlString(entityId.getEntityType().name())
        + " AND entity_id="
        + sqlString(entityId.getId().toString())
        + " AND key="
        + sqlString(key)
        + " AND time >= "
        + query.getStartTs()
        + " AND time < "
        + query.getEndTs();
  }

  private static Aggregation aggregationOf(ReadTsKvQuery query) {
    var aggregationParams = query.getAggParameters();
    Aggregation aggregation = aggregationParams == null ? null : aggregationParams.getAggregation();
    return aggregation == null ? Aggregation.NONE : aggregation;
  }

  private KvEntry kvEntry(String key, TypedKvValue value) {
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
    throw new IllegalArgumentException("Telemetry row does not contain a typed value");
  }

  private static String sqlOrder(String order) {
    String normalized = Objects.requireNonNull(order, "order").trim().toUpperCase(Locale.ROOT);
    if (!"ASC".equals(normalized) && !"DESC".equals(normalized)) {
      throw new IllegalArgumentException("Unsupported IoTDB Table Mode read order: " + order);
    }
    return normalized;
  }

  private static String sqlString(String value) {
    return "'" + Objects.requireNonNull(value, "value").replace("'", "''") + "'";
  }

  private <T> ListenableFuture<T> submitReadTask(Callable<T> callable) {
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
        task.fail(
            new IoTDBTableReadQueueFullException(
                "IoTDB Table Mode timeseries read queue is full", e));
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

  private void failDroppedReadTask(Runnable dropped, IoTDBTableDaoShuttingDownException failure) {
    if (dropped instanceof ReadTask<?> task) {
      task.fail(failure);
      readTasks.remove(task);
    }
  }

  private IoTDBTableDaoShuttingDownException shuttingDownException() {
    return new IoTDBTableDaoShuttingDownException(
        "IoTDB Table Mode timeseries DAO is shutting down");
  }

  private static String requireTelemetryKey(String key) {
    if (key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException("Telemetry key must not be blank");
    }
    return key;
  }

  private static ThreadFactory readThreadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread =
          new Thread(runnable, "iotdb-table-timeseries-read-worker-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private int dataPointDays(TsKvEntry tsKvEntry, long ttl) {
    long effectiveTtlSeconds =
        ttl <= 0L
            ? defaultTtlSeconds
            : (defaultTtlSeconds > 0L ? Math.min(defaultTtlSeconds, ttl) : ttl);
    long ttlDays = Math.max(1L, effectiveTtlSeconds / SECONDS_PER_DAY);
    // Saturate at Integer.MAX_VALUE rather than throwing: a data-point-day accounting overflow must
    // never fail an otherwise-valid telemetry write. dataPoints and ttlDays are both >= 0 here.
    long dataPointDays = (long) tsKvEntry.getDataPoints() * ttlDays;
    return dataPointDays > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) dataPointDays;
  }

  private Object typedValue(TsKvEntry tsKvEntry) {
    DataType dataType = tsKvEntry.getDataType();
    return switch (dataType) {
      case BOOLEAN -> requiredValue(tsKvEntry.getBooleanValue(), dataType);
      case LONG -> requiredValue(tsKvEntry.getLongValue(), dataType);
      case DOUBLE -> requiredValue(tsKvEntry.getDoubleValue(), dataType);
      case STRING -> requiredValue(tsKvEntry.getStrValue(), dataType);
      case JSON -> requiredValue(tsKvEntry.getJsonValue(), dataType);
    };
  }

  private Object requiredValue(Optional<?> value, DataType dataType) {
    return value.orElseThrow(
        () -> new IllegalArgumentException("Missing value for telemetry data type " + dataType));
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
