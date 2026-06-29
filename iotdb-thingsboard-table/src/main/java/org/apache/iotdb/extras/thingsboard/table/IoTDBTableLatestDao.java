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
import com.google.common.util.concurrent.Striped;
import lombok.extern.slf4j.Slf4j;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Repository;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DataType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.locks.Lock;

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
 * <p>Design (derived latest + a minimal per-key latest overlay): the latest value for an entity/key
 * is read from BOTH the historical {@code telemetry} table (derived) AND a small {@code
 * telemetry_latest} overlay table, merged by the maximum timestamp per key (the overlay wins an
 * exact tie). The derived read stays primary and is engine-accelerated by IoTDB Table Mode's native
 * last cache ({@code ORDER BY time DESC LIMIT 1} and {@code LAST_BY(col, time)}); the overlay
 * supplements it.
 *
 * <p><b>Why the overlay is written on EVERY {@code saveLatest} (mentor-confirmed, PR-2 review
 * flag):</b> the module's historical {@code save()} write path is ASYNCHRONOUS and batched, so at
 * the moment {@code saveLatest} runs it cannot see whether a paired {@code telemetry} row will be
 * written (a normal full-save) or not (a latest-only write, e.g. the EntityView telemetry-copy
 * {@code LATEST_AND_WS} / {@code saveTs=false} path). It therefore cannot distinguish latest-only
 * from full-save and writes the overlay UNCONDITIONALLY (delete-then-insert, one row per identity).
 * This closes the data-loss gap for latest-only writes that the earlier no-shadow-table design left
 * open, at the cost of one extra overlay write per latest update (equivalent to the standard
 * ThingsBoard latest-table behavior). The overlay is effectively a latest table, so this partially
 * reverses the Phase-1 "no shadow table" choice; it is flagged here for the mentor's PR-2 review.
 *
 * <ul>
 *   <li>{@link #saveLatest} performs a tag-only {@code DELETE} of the identity in {@code
 *       telemetry_latest} followed by an {@code INSERT} at {@code time = tsKvEntry.getTs()} with
 *       exactly one typed FIELD set, both under a per-identity in-JVM lock so concurrent
 *       same-identity writes converge to a single overlay row. It returns a {@code null} version.
 *   <li>{@link #findLatestOpt}/{@link #findLatest}/{@link #findAllLatest} read the derived latest
 *       and the overlay and merge them by max timestamp per key (overlay wins on tie). The merge is
 *       not additive (max-by-ts), so a key present in both stores is never double-counted.
 *   <li>{@link #removeLatest} snapshots the merged latest under the per-identity lock, and when
 *       that latest is inside the half-open {@code [startTs, endTs)} delete window: deletes the
 *       overlay row, and — if {@code rewriteLatestIfDeleted} is set — resurrects the next-older
 *       historical value ({@code telemetry} where {@code time < startTs}) back into the overlay and
 *       returns it as {@code getData()} (so {@code onTimeSeriesDelete} emits a WS UPDATE rather
 *       than a DELETE).
 * </ul>
 *
 * <p>Residual Phase-1 limitations (documented, flagged for the mentor's PR-2 review):
 *
 * <ul>
 *   <li><b>{@code version} is always {@code null}.</b> IoTDB has no SQL sequence (same as the
 *       Cassandra backend); type-correct and contract-legal, but the TB EDQS notifications that key
 *       off a non-null version are not driven in Phase-1.
 *   <li><b>Telemetry-derived race residual.</b> The overlay-backed value is race-free under the
 *       per-identity lock, but the historical {@code remove} runs as a SEPARATE future from {@code
 *       removeLatest}; a purely telemetry-derived (full-save) latest can transiently still be read
 *       from {@code telemetry} until that historical delete commits. Eventually consistent.
 *   <li><b>Overlay growth.</b> {@code telemetry_latest} is {@code TTL='INF'} with no entity-level
 *       cleanup, so under unbounded key cardinality it grows without bound (one row per identity;
 *       bounded for normal key sets).
 *   <li><b>Same-timestamp cross-store type change.</b> The overlay wins an exact-ts tie, continuing
 *       the documented B1 same-timestamp limitation.
 * </ul>
 *
 * <p>The key-discovery SPI methods ({@link #findAllKeysByDeviceProfileId}, {@link
 * #findAllKeysByEntityIds}, {@link #findAllKeysByEntityIdsAsync}) are deferred to GSOC-304 Wk 9,
 * and the batch {@link #findLatestByEntityIds}/{@link #findLatestByEntityIdsAsync} pair (new in
 * ThingsBoard v4.3.1.2) to GSOC-304 Wk 10; all currently throw {@link
 * UnsupportedOperationException}.
 *
 * @see "GSOC-304 design doc section 6.0"
 * @see "GSOC-304 latest-overlay design note"
 * @since GSOC-304 Wk 4 latest DAO
 */
@Slf4j
@Repository
@ConditionalOnBean(name = IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
@Conditional(IoTDBTableLatestEnabledCondition.class)
public class IoTDBTableLatestDao extends IoTDBTableBaseDao
    implements TimeseriesLatestDao, DisposableBean {
  private static final String TABLE_NAME = IoTDBTableTimeseriesWriter.TABLE_NAME;
  static final String TABLE_LATEST = "telemetry_latest";
  private static final String SELECT_TYPED_COLUMNS =
      "time, bool_v, long_v, double_v, str_v, json_v";

  // NUL is the identity-lock key separator: it cannot appear in any tenant/entity UUID,
  // entity-type enum name, or telemetry key, so distinct identities can never collide into the same
  // Striped lock stripe by string concatenation.
  private static final char LOCK_KEY_SEPARATOR = '\u0000';

  // The three parallel arrays below follow the telemetry_latest DDL tag order
  // (schema-iotdb-table-latest.sql): entity_type, tenant_id, key, entity_id (TAGs), then bool_v,
  // long_v, double_v, str_v, json_v (FIELDs) — the SAME shape as the historical telemetry table, so
  // getEntry()/kvEntry() row mapping is reused. They must stay positionally aligned and cover
  // exactly the 9 non-time columns; the `time TIMESTAMP TIME` column is written through
  // Tablet#addTimestamp (NOT a ColumnCategory.TIME entry). Rebuilding with a different tag order is
  // a correctness bug (TAG-order rot).
  private static final List<String> COLUMN_NAMES =
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
  private static final List<TSDataType> DATA_TYPES =
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
  private static final List<ColumnCategory> COLUMN_CATEGORIES =
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

  private final ThreadPoolExecutor readExecutor;
  private final java.util.Set<ReadTask<?>> readTasks = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean destroyed = new AtomicBoolean(false);
  private final long shutdownDrainTimeoutMs;
  // Per-identity write/snapshot serialization for the overlay (single-JVM convergence). Only
  // saveLatest and removeLatest take it; the merged reads (findLatest/findAllLatest) stay
  // best-effort/unlocked like the derived reads.
  private final Striped<Lock> identityLocks = Striped.lock(256);

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
    String key = requireTelemetryKey(tsKvEntry.getKey());
    // The async/batched historical save() cannot be observed here, so saveLatest cannot tell a
    // latest-only write from a full-save and writes the per-key overlay UNCONDITIONALLY
    // (delete-then-insert under a per-identity lock). This closes the latest-only data-loss gap
    // (e.g. EntityView LATEST_AND_WS / saveTs=false) that a no-shadow-table no-op would drop. See
    // the class javadoc (overlay rationale + PR-2 review flags).
    Lock lock = identityLock(tenantId, entityId, key);
    return submitReadTask(
        () -> {
          lock.lock();
          try {
            upsertOverlay(tenantId, entityId, tsKvEntry, key);
          } finally {
            lock.unlock();
          }
          // IoTDB has no sequence; Phase-1 returns a null version (see class javadoc).
          return null;
        });
  }

  @Override
  public ListenableFuture<TsKvLatestRemovingResult> removeLatest(
      TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(query, "query");
    String telemetryKey = requireTelemetryKey(query.getKey());
    // Overlay-aware remove under the per-identity lock: snapshot the merged latest, and when it is
    // inside the half-open [startTs, endTs) delete window delete the overlay row (and optionally
    // resurrect the next-older historical value into the overlay). The result is HONEST about
    // whether a latest value was actually affected, because TB consumes isRemoved() / getData() as
    // real delete/update signals (DefaultTelemetrySubscriptionService.onTimeSeriesDelete).
    Lock lock = identityLock(tenantId, entityId, telemetryKey);
    return submitReadTask(
        () -> {
          lock.lock();
          try {
            return doRemoveLatest(tenantId, entityId, query, telemetryKey);
          } finally {
            lock.unlock();
          }
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

  // ---- read merge (derived primary + overlay, max-ts-per-key, overlay wins on tie) ----

  private Optional<TsKvEntry> doFindLatest(TenantId tenantId, EntityId entityId, String key)
      throws Exception {
    // Derived read FIRST: getEntry fail-fast (IllegalStateException on >1 typed column, the
    // documented B1 same-timestamp limitation) propagates so the future fails rather than returning
    // bad data; the overlay read (delete-then-insert => single typed column) cannot trip it.
    Optional<TsKvEntry> derived =
        readLatestRow(buildFindLatestSql(tenantId, entityId, key), key, "time");
    Optional<TsKvEntry> overlay =
        readLatestRow(buildFindLatestOverlaySql(tenantId, entityId, key), key, "time");
    return mergeLatest(derived, overlay);
  }

  private List<TsKvEntry> doFindAllLatest(TenantId tenantId, EntityId entityId) throws Exception {
    Map<String, TsKvEntry> byKey = new LinkedHashMap<>();
    // Derived: LAST_BY(col, time) + MAX(time) GROUP BY key gives one synthetic sparse row per key.
    readEntriesInto(byKey, buildFindAllLatestSql(tenantId, entityId), "last_ts", false);
    // Overlay: exactly one row per identity (delete-then-insert). Merge by max ts per key: overlay
    // wins an exact tie and supplies any latest-only key the derived store never saw.
    readEntriesInto(byKey, buildFindAllLatestOverlaySql(tenantId, entityId), "time", true);
    return new ArrayList<>(byKey.values());
  }

  private Optional<TsKvEntry> readLatestRow(String sql, String key, String tsColumn)
      throws Exception {
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      if (!row.next()) {
        return Optional.empty();
      }
      TypedKvValue value = getEntry(row);
      if (!value.hasValue()) {
        return Optional.empty();
      }
      long ts = row.getTimestamp(tsColumn).getTime();
      return Optional.of(new BasicTsKvEntry(ts, kvEntry(key, value)));
    }
  }

  private void readEntriesInto(
      Map<String, TsKvEntry> byKey, String sql, String tsColumn, boolean overlayWins)
      throws Exception {
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        String key = row.getString("key");
        // B1 fail-fast: getEntry throws IllegalStateException if a key's row has more than one
        // typed column; the whole findAllLatest future then fails rather than silently skipping it.
        TypedKvValue value = getEntry(row);
        if (!value.hasValue()) {
          continue;
        }
        long ts = row.getTimestamp(tsColumn).getTime();
        TsKvEntry entry = new BasicTsKvEntry(ts, kvEntry(key, value));
        if (overlayWins) {
          // remap (existing=derived, incoming=overlay): overlay wins on tie (>=).
          byKey.merge(
              key,
              entry,
              (existing, incoming) -> incoming.getTs() >= existing.getTs() ? incoming : existing);
        } else {
          byKey.put(key, entry);
        }
      }
    }
  }

  private static Optional<TsKvEntry> mergeLatest(
      Optional<TsKvEntry> derived, Optional<TsKvEntry> overlay) {
    if (derived.isEmpty()) {
      return overlay;
    }
    if (overlay.isEmpty()) {
      return derived;
    }
    // Max ts per key; the overlay wins an exact tie (continues the B1 same-timestamp limitation).
    return overlay.get().getTs() >= derived.get().getTs() ? overlay : derived;
  }

  // ---- overlay write (delete-then-insert) ----

  private void upsertOverlay(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, String key)
      throws Exception {
    String deleteSql = buildDeleteLatestSql(tenantId, entityId, key);
    Tablet tablet = buildLatestTablet(tenantId, entityId, tsKvEntry, key);
    try (ITableSession session = tableSessionPool.getSession()) {
      // Step 1: tag-only DELETE removes the identity's overlay row across all time (no time
      // predicate). Step 2: insert the single current row at time = tsKvEntry.getTs().
      session.executeNonQueryStatement(deleteSql);
      session.insert(tablet);
    }
  }

  private void deleteOverlay(TenantId tenantId, EntityId entityId, String key) throws Exception {
    String sql = buildDeleteLatestSql(tenantId, entityId, key);
    try (ITableSession session = tableSessionPool.getSession()) {
      session.executeNonQueryStatement(sql);
    }
  }

  private Tablet buildLatestTablet(
      TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, String key) {
    Tablet tablet = new Tablet(TABLE_LATEST, COLUMN_NAMES, DATA_TYPES, COLUMN_CATEGORIES, 1);
    // telemetry_latest declares an explicit `time TIMESTAMP TIME` column in DDL, but it is still
    // the table's time column: written through the normal tablet timestamp mechanism (NOT a
    // ColumnCategory.TIME entry). Use the entry's timestamp as the row time.
    tablet.addTimestamp(0, tsKvEntry.getTs());
    // TAG values, in the DDL tag order (entity_type, tenant_id, key, entity_id) — telemetry shape.
    tablet.addValue("entity_type", 0, entityId.getEntityType().name());
    tablet.addValue("tenant_id", 0, tenantId.getId().toString());
    tablet.addValue("key", 0, key);
    tablet.addValue("entity_id", 0, entityId.getId().toString());
    // FIELD values: exactly one typed column is non-null, chosen by the entry's DataType.
    DataType dataType = tsKvEntry.getDataType();
    tablet.addValue("bool_v", 0, dataType == DataType.BOOLEAN ? tsKvEntry.getValue() : null);
    tablet.addValue("long_v", 0, dataType == DataType.LONG ? tsKvEntry.getValue() : null);
    tablet.addValue("double_v", 0, dataType == DataType.DOUBLE ? tsKvEntry.getValue() : null);
    tablet.addValue("str_v", 0, dataType == DataType.STRING ? tsKvEntry.getValue() : null);
    tablet.addValue("json_v", 0, dataType == DataType.JSON ? tsKvEntry.getValue() : null);
    tablet.setRowSize(1);
    return tablet;
  }

  // ---- overlay-aware remove ----

  private TsKvLatestRemovingResult doRemoveLatest(
      TenantId tenantId, EntityId entityId, DeleteTsKvQuery query, String key) throws Exception {
    // (1) Snapshot the merged latest (derived + overlay) under the per-identity lock; the overlay
    // part of the snapshot is race-free wrt concurrent saveLatest/removeLatest on this identity.
    Optional<TsKvEntry> latest = doFindLatest(tenantId, entityId, key);
    if (latest.isEmpty()) {
      return new TsKvLatestRemovingResult(key, false);
    }
    long ts = latest.get().getTs();
    boolean inWindow = ts >= query.getStartTs() && ts < query.getEndTs();
    if (!inWindow) {
      // (4) the current latest is outside the half-open [startTs, endTs) window: nothing removed,
      // so TB is not told to delete a latest value that is still valid.
      return new TsKvLatestRemovingResult(key, false);
    }
    // TB only invokes removeLatest with deleteLatest=true (BaseTimeseriesService gates it); the
    // check here is defensive — when false, do not mutate the overlay and report nothing removed.
    boolean deleteLatest = !Boolean.FALSE.equals(query.getDeleteLatest());
    if (!deleteLatest) {
      return new TsKvLatestRemovingResult(key, false);
    }
    boolean rewrite = Boolean.TRUE.equals(query.getRewriteLatestIfDeleted());
    if (rewrite) {
      // (5) resurrect the next-older historical value (telemetry, time < startTs) as the new latest
      // by writing it into the overlay, and return it as data so onTimeSeriesDelete emits a WS
      // UPDATE (removed=true, getData()=prior).
      Optional<TsKvEntry> prior = doFindHistoryBefore(tenantId, entityId, key, query.getStartTs());
      if (prior.isPresent()) {
        upsertOverlay(tenantId, entityId, prior.get(), key);
        return new TsKvLatestRemovingResult(prior.get(), null);
      }
      // No older history to resurrect: fall through to a plain latest delete.
    }
    // (3 + 6) delete the overlay row for this identity and report a real latest delete (WS DELETE).
    deleteOverlay(tenantId, entityId, key);
    return new TsKvLatestRemovingResult(key, true, null);
  }

  private Optional<TsKvEntry> doFindHistoryBefore(
      TenantId tenantId, EntityId entityId, String key, long startTs) throws Exception {
    return readLatestRow(buildRewriteHistorySql(tenantId, entityId, key, startTs), key, "time");
  }

  // ---- SQL builders ----

  private String buildFindLatestSql(TenantId tenantId, EntityId entityId, String key) {
    return "SELECT "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE "
        + identityPredicate(tenantId, entityId, key)
        + " ORDER BY time DESC LIMIT 1";
  }

  private String buildFindLatestOverlaySql(TenantId tenantId, EntityId entityId, String key) {
    return "SELECT "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_LATEST
        + " WHERE "
        + identityPredicate(tenantId, entityId, key)
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
        + " WHERE "
        + entityPredicate(tenantId, entityId)
        + " GROUP BY key";
  }

  private String buildFindAllLatestOverlaySql(TenantId tenantId, EntityId entityId) {
    // Each identity holds exactly one overlay row (delete-then-insert), so no aggregation is
    // needed.
    return "SELECT key, "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_LATEST
        + " WHERE "
        + entityPredicate(tenantId, entityId);
  }

  private String buildDeleteLatestSql(TenantId tenantId, EntityId entityId, String key) {
    return "DELETE FROM " + TABLE_LATEST + " WHERE " + identityPredicate(tenantId, entityId, key);
  }

  private String buildRewriteHistorySql(
      TenantId tenantId, EntityId entityId, String key, long startTs) {
    return "SELECT "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE "
        + identityPredicate(tenantId, entityId, key)
        + " AND time < "
        + startTs
        + " ORDER BY time DESC LIMIT 1";
  }

  private String identityPredicate(TenantId tenantId, EntityId entityId, String key) {
    return entityPredicate(tenantId, entityId) + " AND key=" + sqlString(key);
  }

  private String entityPredicate(TenantId tenantId, EntityId entityId) {
    return "tenant_id="
        + sqlString(tenantId.getId().toString())
        + " AND entity_type="
        + sqlString(entityId.getEntityType().name())
        + " AND entity_id="
        + sqlString(entityId.getId().toString());
  }

  // ---- mapping + helpers ----

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

  private Lock identityLock(TenantId tenantId, EntityId entityId, String key) {
    return identityLocks.get(
        tenantId.getId().toString()
            + LOCK_KEY_SEPARATOR
            + entityId.getEntityType().name()
            + LOCK_KEY_SEPARATOR
            + entityId.getId().toString()
            + LOCK_KEY_SEPARATOR
            + key);
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
        task.fail(new IoTDBTableReadQueueFullException("IoTDB Table Mode latest queue is full", e));
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
