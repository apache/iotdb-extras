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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Repository;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * Latest telemetry DAO for the IoTDB Table Mode backend.
 *
 * <p>Spring activation ({@link IoTDBTableLatestEnabledCondition}): inert unless ALL of {@code
 * database.ts.type=iotdb-table}, {@code database.ts_latest.type=iotdb-table} and {@code
 * iotdb.ts.experimental-raw-only=true} are set. Requiring {@code database.ts.type=iotdb-table} (not
 * just the latest selector) closes the split-config gap: the derived latest reads the {@code
 * telemetry} table that only the IoTDB writer populates, so it must never activate without that
 * writer.
 *
 * <p>Mentor-decided design (single-table derived latest, no shadow table): the latest value for an
 * entity/key is derived directly from the historical {@code telemetry} table. IoTDB Table Mode has
 * a native engine-level last cache (TableDeviceLastCache / LastQueryAggTableScanOperator), so the
 * {@code ORDER BY time DESC LIMIT 1} and {@code LAST_BY(col, time)} queries used here are
 * engine-accelerated. No separate {@code latest_telemetry} store is created or written.
 *
 * <p>Consequences of the no-shadow-table contract:
 *
 * <ul>
 *   <li>{@link #saveLatest} is a no-op on the normal full-save path (the paired {@code save()}
 *       already wrote the row the derived query picks up). KNOWN Phase-1 LIMITATION (data loss,
 *       flagged for the mentor): TB's latest-only write paths (EntityView {@code LATEST_AND_WS},
 *       {@code saveTs=false}) call {@code saveLatest} with no paired {@code save()}, so this no-op
 *       silently drops that value. See the method comment.
 *   <li>{@link #removeLatest} performs no independent storage mutation; the derived latest follows
 *       the historical {@code remove} (Wk3). KNOWN Phase-1 LIMITATIONS (flagged for the mentor): it
 *       cannot fully honor the contract for latest-only deletes / the rewrite-version result. See
 *       the method comment.
 * </ul>
 *
 * <p>The two {@code saveLatest}/{@code removeLatest} limitations above stem from the agreed Phase-1
 * "no shadow latest table" design and are open mentor-decision items, not benign no-ops.
 *
 * <p>The key-discovery SPI methods ({@link #findAllKeysByDeviceProfileId}, {@link
 * #findAllKeysByEntityIds}, {@link #findAllKeysByEntityIdsAsync}) are deferred to GSOC-304 Wk 9,
 * and the batch {@link #findLatestByEntityIds}/{@link #findLatestByEntityIdsAsync} pair (new in
 * ThingsBoard v4.3.1.2) to GSOC-304 Wk 10; all currently throw {@link
 * UnsupportedOperationException}.
 *
 * @see "GSOC-304 design doc section 6.0"
 * @since GSOC-304 Wk 4 latest DAO
 */
@Slf4j
@Repository
@ConditionalOnBean(name = IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
@Conditional(IoTDBTableLatestEnabledCondition.class)
public class IoTDBTableLatestDao extends IoTDBTableBaseDao
    implements TimeseriesLatestDao, DisposableBean {
  private static final String TABLE_NAME = IoTDBTableTimeseriesWriter.TABLE_NAME;
  private static final String SELECT_TYPED_COLUMNS =
      "time, bool_v, long_v, double_v, str_v, json_v";

  private final ThreadPoolExecutor readExecutor;
  private final java.util.Set<ReadTask<?>> readTasks = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean destroyed = new AtomicBoolean(false);
  private final long shutdownDrainTimeoutMs;

  public IoTDBTableLatestDao(ITableSessionPool tableSessionPool, IoTDBTableConfig config) {
    super(tableSessionPool);
    this.shutdownDrainTimeoutMs = config.getTs().getSave().getShutdownDrainTimeoutMs();
    int readThreads = config.getTs().getRead().getThreads();
    int readQueueCapacity = config.getTs().getRead().getQueueCapacity();
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
  public ListenableFuture<Optional<TsKvEntry>> findLatestOpt(
      TenantId tenantId, EntityId entityId, String key) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    String telemetryKey = requireTelemetryKey(key);
    return submitReadTask(() -> doFindLatest(tenantId, entityId, telemetryKey));
  }

  @Override
  public ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    String telemetryKey = requireTelemetryKey(key);
    return submitReadTask(
        () ->
            doFindLatest(tenantId, entityId, telemetryKey)
                .orElseGet(() -> nullEntry(telemetryKey)));
  }

  @Override
  public ListenableFuture<List<TsKvEntry>> findAllLatest(TenantId tenantId, EntityId entityId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    return submitReadTask(() -> doFindAllLatest(tenantId, entityId));
  }

  @Override
  public ListenableFuture<Long> saveLatest(
      TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(tsKvEntry, "tsKvEntry");
    // Single-table derived latest (no shadow table): on the NORMAL full-save path a paired aligned
    // save() already wrote the row to the telemetry table, so the derived ORDER BY time DESC LIMIT
    // 1
    // read picks it up and there is no separate latest store to update here.
    //
    // KNOWN Phase-1 limitation (no shadow latest table) -- DATA LOSS, flagged for the mentor: TB
    // also has LATEST-ONLY write paths where saveLatest is called WITHOUT a paired save(). The
    // EntityView telemetry-copy (BaseTimeseriesService.saveLatest -> doSave(saveTs=false,
    // saveLatest=true), TimeseriesSaveRequest.Strategy.LATEST_AND_WS) is the load-bearing example.
    // Because nothing writes the telemetry row, this no-op silently DROPS that latest value and it
    // is afterwards unreadable via findLatest/findAllLatest. Honoring latest-only writes needs a
    // real
    // latest store/overlay; this is deferred Phase-1 pending the mentor's decision (add a minimal
    // latest overlay vs. document EntityView-latest as unsupported in Phase-1).
    //
    // A null Long version is returned (type-correct, matching the Cassandra backend's nullable
    // version) so the future still completes successfully as the SPI requires.
    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<TsKvLatestRemovingResult> removeLatest(
      TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(query, "query");
    String telemetryKey = requireTelemetryKey(query.getKey());
    // No independent storage mutation: the historical Wk3 remove already deleted the underlying
    // telemetry rows and the derived latest follows automatically. The result must still be HONEST
    // about whether a latest value was actually affected, because TB consumes isRemoved() as a real
    // delete signal (DefaultTelemetrySubscriptionService). Mirroring the reference
    // SqlTimeseriesLatestDao:
    // removed=true only when the current latest exists AND its timestamp falls inside the half-open
    // [startTs, endTs) delete window; otherwise removed=false.
    //
    // KNOWN Phase-1 limitations (no shadow latest table), all flagged for the mentor -- the
    // single-table derived-latest design cannot fully honor the removeLatest contract that TB
    // exercises independently of the historical store:
    //   (a) False-negative race: TB's BaseTimeseriesService submits the historical remove and this
    //       removeLatest as SEPARATE futures. If the historical delete commits BEFORE this derived
    //       read runs, the read sees an empty/older latest and reports removed=FALSE even though
    // the
    //       pre-delete latest WAS inside the window, suppressing the latest-delete notification.
    //   (b) False-positive on a LATEST-ONLY delete: TB has a delete path that removes only the
    //       latest without a historical delete. This no-op mutates nothing, so the telemetry row
    //       survives and the very next findLatest returns the same value -- yet we may report
    //       removed=TRUE, emitting a spurious delete signal (TB consumes isRemoved() as real).
    //   (c) Dropped rewrite/version: upstream's rewriteLatestIfDeleted can return
    //       TsKvLatestRemovingResult(entry, version) carrying a rewritten latest that
    //       DefaultTelemetrySubscriptionService.onTimeSeriesDelete reads via getData() to choose
    //       update-vs-delete. We always return data=null/version=null, dropping that signal.
    // A robust fix needs a real latest shadow/state (stable pre-delete snapshot + rewrite/version);
    // deferred Phase-1, pending the mentor's decision. The result below is best-effort:
    // removed=true
    // only when the current derived latest exists AND its ts is in the half-open [startTs, endTs).
    return submitReadTask(
        () -> {
          Optional<TsKvEntry> latest = doFindLatest(tenantId, entityId, telemetryKey);
          if (latest.isEmpty()) {
            return new TsKvLatestRemovingResult(telemetryKey, false);
          }
          long ts = latest.get().getTs();
          boolean removed = ts >= query.getStartTs() && ts < query.getEndTs();
          return new TsKvLatestRemovingResult(telemetryKey, removed, null);
        });
  }

  @Override
  public List<String> findAllKeysByDeviceProfileId(
      TenantId tenantId, DeviceProfileId deviceProfileId) {
    // Key discovery is deferred to GSOC-304 Wk 9 (design doc 6.2 "key discovery methods").
    throw new UnsupportedOperationException(
        "IoTDB Table Mode latest key discovery not implemented yet (GSOC-304 Wk 9)");
  }

  @Override
  public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    // Key discovery is deferred to GSOC-304 Wk 9 (design doc 6.2 "key discovery methods").
    throw new UnsupportedOperationException(
        "IoTDB Table Mode latest key discovery not implemented yet (GSOC-304 Wk 9)");
  }

  @Override
  public ListenableFuture<List<String>> findAllKeysByEntityIdsAsync(
      TenantId tenantId, List<EntityId> entityIds) {
    // Key discovery is deferred to GSOC-304 Wk 9 (design doc 6.2 "key discovery methods").
    throw new UnsupportedOperationException(
        "IoTDB Table Mode latest key discovery not implemented yet (GSOC-304 Wk 9)");
  }

  @Override
  public List<TsKvEntry> findLatestByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    // Batch latest read (new in ThingsBoard v4.3.1.2) is deferred to GSOC-304 Wk 10
    // (design doc 6.2 "findLatestByEntityIds batch optimization").
    throw new UnsupportedOperationException(
        "IoTDB Table Mode batch latest read not implemented yet (GSOC-304 Wk 10)");
  }

  @Override
  public ListenableFuture<List<TsKvEntry>> findLatestByEntityIdsAsync(
      TenantId tenantId, List<EntityId> entityIds) {
    // Batch latest read (new in ThingsBoard v4.3.1.2) is deferred to GSOC-304 Wk 10
    // (design doc 6.2 "findLatestByEntityIds batch optimization").
    throw new UnsupportedOperationException(
        "IoTDB Table Mode batch latest read not implemented yet (GSOC-304 Wk 10)");
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
  }

  private Optional<TsKvEntry> doFindLatest(TenantId tenantId, EntityId entityId, String key)
      throws Exception {
    String sql = buildFindLatestSql(tenantId, entityId, key);
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      if (!row.next()) {
        return Optional.empty();
      }
      // B1 fail-fast: getEntry throws IllegalStateException if the selected latest row has more
      // than one typed column set (the documented Phase-1 same-timestamp type-change limitation).
      // The exception propagates so the future fails rather than returning bad data.
      TypedKvValue value = getEntry(row);
      if (!value.hasValue()) {
        return Optional.empty();
      }
      long ts = row.getTimestamp("time").getTime();
      return Optional.of(new BasicTsKvEntry(ts, kvEntry(key, value)));
    }
  }

  private List<TsKvEntry> doFindAllLatest(TenantId tenantId, EntityId entityId) throws Exception {
    String sql = buildFindAllLatestSql(tenantId, entityId);
    List<TsKvEntry> entries = new ArrayList<>();
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        String key = row.getString("key");
        // LAST_BY(col, time) returns col at the row with the maximum time, preserving null, so the
        // aggregated columns form a single synthetic sparse row: exactly one typed column is
        // non-null for a clean key and no value is backfilled from an older different-type row.
        // B1 fail-fast: getEntry throws IllegalStateException if a key's aggregated row has more
        // than one non-null typed column; the whole findAllLatest future then fails rather than
        // silently skipping the bad key.
        TypedKvValue value = getEntry(row);
        if (!value.hasValue()) {
          continue;
        }
        long ts = row.getTimestamp("last_ts").getTime();
        entries.add(new BasicTsKvEntry(ts, kvEntry(key, value)));
      }
    }
    return entries;
  }

  private String buildFindLatestSql(TenantId tenantId, EntityId entityId, String key) {
    return "SELECT "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE tenant_id="
        + sqlString(tenantId.getId().toString())
        + " AND entity_type="
        + sqlString(entityId.getEntityType().name())
        + " AND entity_id="
        + sqlString(entityId.getId().toString())
        + " AND key="
        + sqlString(key)
        + " ORDER BY time DESC LIMIT 1";
  }

  private String buildFindAllLatestSql(TenantId tenantId, EntityId entityId) {
    // GROUP BY key projects the key tag plus the per-column LAST_BY aggregate (value at max time)
    // and MAX(time) for the entry timestamp. The other tags are fixed by the WHERE clause, so they
    // do not need to be (and cannot be) projected as bare columns alongside GROUP BY key.
    return "SELECT key,"
        + " LAST_BY(bool_v, time) AS bool_v,"
        + " LAST_BY(long_v, time) AS long_v,"
        + " LAST_BY(double_v, time) AS double_v,"
        + " LAST_BY(str_v, time) AS str_v,"
        + " LAST_BY(json_v, time) AS json_v,"
        + " MAX(time) AS last_ts"
        + " FROM "
        + TABLE_NAME
        + " WHERE tenant_id="
        + sqlString(tenantId.getId().toString())
        + " AND entity_type="
        + sqlString(entityId.getEntityType().name())
        + " AND entity_id="
        + sqlString(entityId.getId().toString())
        + " GROUP BY key";
  }

  private static TsKvEntry nullEntry(String key) {
    // SPI contract: findLatest returns this sentinel when the value is not present in the DB.
    return new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry(key, null));
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

  private static String sqlString(String value) {
    return "'" + Objects.requireNonNull(value, "value").replace("'", "''") + "'";
  }

  private static String requireTelemetryKey(String key) {
    if (key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException("Telemetry key must not be blank");
    }
    return key;
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
            new IoTDBTableReadQueueFullException("IoTDB Table Mode latest read queue is full", e));
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
    return new IoTDBTableDaoShuttingDownException("IoTDB Table Mode latest DAO is shutting down");
  }

  private static ThreadFactory readThreadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread =
          new Thread(runnable, "iotdb-table-latest-read-worker-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
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
