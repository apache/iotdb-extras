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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>Why the overlay is written on EVERY {@code saveLatest}:</b> the module's historical {@code
 * save()} write path is ASYNCHRONOUS and batched, so at the moment {@code saveLatest} runs it
 * cannot see whether a paired {@code telemetry} row will be written (a normal full-save) or not (a
 * latest-only write, e.g. the EntityView telemetry-copy {@code LATEST_AND_WS} / {@code
 * saveTs=false} path). It therefore cannot distinguish latest-only from full-save and writes the
 * overlay on every save (delete-then-insert, one row per identity), except a backdated write
 * strictly older than the current latest, which the max-ts guard skips. This closes the data-loss
 * gap for latest-only writes that the earlier no-shadow-table design left open, at the cost of one
 * extra overlay write per latest update (equivalent to the standard ThingsBoard latest-table
 * behavior). The overlay is effectively a latest table, so this partially reverses the Phase-1 "no
 * shadow table" choice.
 *
 * <ul>
 *   <li>{@link #saveLatest} performs a tag-only {@code DELETE} of the identity in {@code
 *       telemetry_latest} followed by an {@code INSERT} at {@code time = tsKvEntry.getTs()} with
 *       exactly one typed FIELD set, both under a per-identity in-JVM lock so concurrent
 *       same-identity writes converge to a single overlay row. It returns a {@code null} version.
 *   <li>{@link #findLatestOpt}/{@link #findLatest}/{@link #findAllLatest} read the derived latest
 *       and the overlay and merge them by max timestamp per key (overlay wins on tie). The merge is
 *       not additive (max-by-ts), so a key present in both stores is never double-counted.
 *   <li>{@link #removeLatest} reads derived and overlay separately under the per-identity lock;
 *       when the merged latest is inside the half-open {@code [startTs, endTs)} delete window it
 *       deletes the overlay row ONLY if the overlay's own ts is in-window (an out-of-window overlay
 *       value survives), and — if {@code rewriteLatestIfDeleted} is set — resurrects the next-older
 *       value across BOTH stores ({@code telemetry} where {@code time < startTs} and an overlay
 *       value older than the window; max-ts-wins) back into the overlay and returns it as {@code
 *       getData()} (so {@code onTimeSeriesDelete} emits a WS UPDATE rather than a DELETE).
 * </ul>
 *
 * <p>Residual Phase-1 limitations (documented):
 *
 * <ul>
 *   <li><b>{@code version} is always {@code null}.</b> IoTDB has no SQL sequence (same as the
 *       Cassandra backend); type-correct and contract-legal, but the TB EDQS notifications that key
 *       off a non-null version are not driven in Phase-1.
 *   <li><b>Telemetry-derived race residual.</b> The overlay-backed value is race-free under the
 *       per-identity lock, but the historical {@code remove} runs as a SEPARATE future from {@code
 *       removeLatest}; a purely telemetry-derived (full-save) latest can transiently still be read
 *       from {@code telemetry} until that historical delete commits. Eventually consistent.
 *   <li><b>Non-atomic overlay write (delete-then-insert).</b> The overlay write deletes the
 *       identity then inserts the new row, and IoTDB has no multi-statement transaction.
 *       Delete-first is REQUIRED so a same-timestamp type change converges to one typed column (an
 *       insert at an existing {@code (tags, time)} merges columns), so the order cannot be reversed
 *       to insert-first. Consequently an {@code INSERT} failure after the {@code DELETE} commits
 *       loses a latest-only (overlay-only) value (a derived full-save value is still recoverable
 *       from {@code telemetry}); the {@code saveLatest} future fails loud and the caller retries.
 *   <li><b>Telemetry-only writes (saveWithoutLatest) surface via the derived read.</b> The latest
 *       is MAX-ts over derived {@code telemetry} and the overlay, so a history-only write ({@code
 *       saveWithoutLatest} / "skip latest persistence") that bumps {@code telemetry} above the last
 *       {@code saveLatest} surfaces as the latest even though no {@code saveLatest} ran. Making the
 *       overlay strictly authoritative when present is an option (not done in Phase-1 to keep the
 *       engine-accelerated derived read primary).
 *   <li><b>removeLatest honesty + overlapping deletes.</b> {@code removeLatest} deletes only an
 *       overlay row whose OWN ts is inside the delete window and (on rewrite) resurrects the
 *       next-older value across BOTH stores, so an out-of-window overlay value is never wiped.
 *       {@code removed=true} still reflects the in-window latest being removed even when that value
 *       is derived-only (deleted by the separate historical future); and a value resurrected by one
 *       rewrite delete that a SECOND overlapping concurrent delete window also covers can
 *       transiently persist in the overlay until the next write. Eventually consistent.
 *   <li><b>Overlay growth.</b> {@code telemetry_latest} is {@code TTL='INF'} with no entity-level
 *       cleanup, so under unbounded key cardinality it grows without bound (one row per identity;
 *       bounded for normal key sets).
 *   <li><b>Single-JVM overlay convergence.</b> The per-identity lock serializing overlay writes is
 *       in-JVM (Striped), so the delete-then-insert converges to one row per identity only within a
 *       node. In a clustered ThingsBoard deployment two nodes writing the same identity
 *       concurrently can transiently leave two overlay rows until the next write (the max-ts read
 *       merge still returns the newer value). The sibling AttributesDao gates this with an explicit
 *       cluster-mode acknowledgement; a symmetric ack for the latest overlay is an option,
 *       documented here for parity in the meantime.
 *   <li><b>Same-timestamp cross-store type change.</b> The overlay wins an exact-ts tie, continuing
 *       the documented same-timestamp two-type-column limitation (B1).
 * </ul>
 *
 * <p>The key-discovery SPI methods ({@link #findAllKeysByEntityIds}, {@link
 * #findAllKeysByEntityIdsAsync}; full derived discovery is a follow-up) and the batch {@link
 * #findLatestByEntityIds}/{@link #findLatestByEntityIdsAsync} pair (new in ThingsBoard v4.3.1.2;
 * derived batch read is a follow-up) return an empty list rather than throwing, because they are
 * reachable in normal operation — {@code findAllKeysByEntityIdsAsync}/{@code
 * findLatestByEntityIdsAsync} back the dashboard {@code POST /api/entitiesQuery/find/keys} lookup
 * (DefaultEntityQueryService#fetchTimeseriesKeys), and the sync {@code findAllKeysByEntityIds}
 * backs entity-delete housekeeping — where a thrown {@link UnsupportedOperationException} would
 * surface as an HTTP 500 / a failed cleanup task. This matches the official {@code
 * CassandraBaseTimeseriesLatestDao}, which returns empty for all four, and {@link
 * #findAllKeysByDeviceProfileId}.
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
  private final Set<ReadTask<?>> readTasks = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean destroyed = new AtomicBoolean(false);
  private final long shutdownDrainTimeoutMs;
  // Per-identity write/snapshot serialization for the overlay (single-JVM convergence). saveLatest,
  // removeLatest and the single-key point reads (findLatest/findLatestOpt) take it; only the
  // multi-key findAllLatest stays best-effort/unlocked like the derived reads.
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
    return submitReadTask(() -> lockedFindLatest(tenantId, entityId, telemetryKey));
  }

  @Override
  public ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    String telemetryKey = requireTelemetryKey(key);
    return submitReadTask(
        () ->
            lockedFindLatest(tenantId, entityId, telemetryKey)
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
    // latest-only write from a full-save and writes the per-key overlay (delete-then-insert under a
    // per-identity lock) on every call EXCEPT when a strictly newer latest already exists (max-ts
    // guard below). This closes the latest-only data-loss gap (e.g. EntityView LATEST_AND_WS /
    // saveTs=false) that a no-shadow-table no-op would drop. See the class javadoc (overlay
    // rationale).
    Lock lock = identityLock(tenantId, entityId, key);
    return submitReadTask(
        () -> {
          lock.lock();
          try {
            // Max-ts-wins, not last-write-wins: skip a backdated saveLatest so an out-of-order
            // write never regresses the latest below a newer value already stored (matches the
            // ThingsBoard ts_kv_latest default ON CONFLICT ... WHERE ts <= excluded.ts). Strict '>'
            // keeps the documented overlay-wins-on-exact-tie (B1) rule. saveLatest already holds
            // the
            // identity lock, so the snapshot reads run directly (no re-lock); currentLatestForGuard
            // is the B1-lenient merge so an ambiguous derived row never blocks every saveLatest.
            Optional<TsKvEntry> current = currentLatestForGuard(tenantId, entityId, key);
            if (current.isEmpty() || current.get().getTs() <= tsKvEntry.getTs()) {
              upsertOverlay(tenantId, entityId, tsKvEntry, key);
            }
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
    // Config-time UI key enumeration (GET /api/deviceProfile/devices/keys/timeseries,
    // TENANT_ADMIN). Return empty rather than throwing so the endpoint degrades gracefully instead
    // of returning 500 — matching the sibling IoTDBTableAttributesDao and the non-relational
    // ThingsBoard backends (e.g. CassandraBaseTimeseriesLatestDao.findAllKeysByDeviceProfileId
    // returns an empty list). Tenant-wide DISTINCT-key discovery (the null-deviceProfileId branch)
    // is a follow-up.
    return Collections.emptyList();
  }

  @Override
  public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    // Reachable in normal operation (entity-delete housekeeping, TelemetryDeletionTaskProcessor),
    // so degrade gracefully rather than throw: the official CassandraBaseTimeseriesLatestDao also
    // returns an empty list. Full derived DISTINCT-key discovery over the telemetry table
    // is a follow-up.
    return Collections.emptyList();
  }

  @Override
  public ListenableFuture<List<String>> findAllKeysByEntityIdsAsync(
      TenantId tenantId, List<EntityId> entityIds) {
    // Backs POST /api/entitiesQuery/find/keys (DefaultEntityQueryService#fetchTimeseriesKeys), the
    // dashboard "available telemetry keys" lookup — a synchronous throw here would surface as an
    // HTTP 500. Mirror CassandraBaseTimeseriesLatestDao and return an empty future; full derived
    // DISTINCT-key discovery is a follow-up.
    return Futures.immediateFuture(Collections.emptyList());
  }

  @Override
  public List<TsKvEntry> findLatestByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    // Batch latest read (new in ThingsBoard v4.3.1.2). Return empty (graceful) rather than throw,
    // matching CassandraBaseTimeseriesLatestDao.findLatestByEntityIds; the derived batch
    // read is a follow-up.
    return Collections.emptyList();
  }

  @Override
  public ListenableFuture<List<TsKvEntry>> findLatestByEntityIdsAsync(
      TenantId tenantId, List<EntityId> entityIds) {
    // Backs the includeSamples branch of POST /api/entitiesQuery/find/keys
    // (DefaultEntityQueryService#fetchTimeseriesKeys); a synchronous throw would surface as an HTTP
    // 500. Mirror CassandraBaseTimeseriesLatestDao and return an empty future; the derived batch
    // read is a follow-up.
    return Futures.immediateFuture(Collections.emptyList());
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

  // Reads a single latest row but TOLERATES the B1 same-timestamp two-type fail-fast (getEntry's
  // >1-typed-column IllegalStateException): returns empty instead of propagating, so an ambiguous
  // telemetry row never blocks a saveLatest guard, a removeLatest snapshot, or a rewrite resurrect.
  // The strict read paths (findLatest/findLatestOpt/findAllLatest) keep surfacing B1 as a fault.
  // The
  // catch is precise: IoTDBTableBaseDao.getEntry is the only IllegalStateException source here.
  private Optional<TsKvEntry> readLatestRowB1Lenient(String sql, String key) throws Exception {
    try {
      return readLatestRow(sql, key, "time");
    } catch (IllegalStateException b1) {
      log.debug(
          "Latest row for key '{}' is an ambiguous same-timestamp two-type row; treating it as "
              + "absent for this guard/snapshot/resurrect",
          key,
          b1);
      return Optional.empty();
    }
  }

  // Derived (telemetry) point-read latest, B1-lenient: used by the saveLatest max-ts guard and the
  // removeLatest snapshot so an ambiguous telemetry row never blocks a write or a delete.
  private Optional<TsKvEntry> readDerivedLatestLenient(
      TenantId tenantId, EntityId entityId, String key) throws Exception {
    return readLatestRowB1Lenient(buildFindLatestSql(tenantId, entityId, key), key);
  }

  // Max-ts guard snapshot for saveLatest: B1-lenient derived + overlay (reads derived first, then
  // overlay — the order the unit tests stub), so a same-timestamp two-type telemetry row cannot
  // make
  // every saveLatest for the identity fail.
  private Optional<TsKvEntry> currentLatestForGuard(
      TenantId tenantId, EntityId entityId, String key) throws Exception {
    Optional<TsKvEntry> derived = readDerivedLatestLenient(tenantId, entityId, key);
    Optional<TsKvEntry> overlay =
        readLatestRowB1Lenient(buildFindLatestOverlaySql(tenantId, entityId, key), key);
    return mergeLatest(derived, overlay);
  }

  // Single-key point reads take the per-identity lock so a concurrent saveLatest/removeLatest
  // delete-then-insert on the same identity is never observed mid-window (for an overlay-only key
  // there is no derived row to mask the gap). The multi-key findAllLatest stays unlocked, matching
  // the sibling IoTDBTableAttributesDao.findAll.
  private Optional<TsKvEntry> lockedFindLatest(TenantId tenantId, EntityId entityId, String key)
      throws Exception {
    Lock lock = identityLock(tenantId, entityId, key);
    lock.lock();
    try {
      return doFindLatest(tenantId, entityId, key);
    } finally {
      lock.unlock();
    }
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

  // Delete-then-insert (NOT insert-then-delete): the tag-only DELETE removes the identity's overlay
  // row across ALL time, then the single current row is inserted at time = tsKvEntry.getTs(). The
  // delete must run first because an IoTDB insert at an existing (tags, time) MERGES typed columns
  // (a null field does not overwrite an existing value), so inserting a different-type value at a
  // same-timestamp row without clearing it first would leave two typed columns (the B1 fail-fast on
  // read). The trade-off is a non-atomic write (IoTDB has no multi-statement transaction): if the
  // INSERT fails after the DELETE commits the prior overlay value is lost (the future fails loud;
  // documented in the class limitations). Insert-first would avoid that loss but re-break the
  // same-timestamp convergence, so it is deliberately not used.
  private void upsertOverlay(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, String key)
      throws Exception {
    String deleteSql = buildDeleteLatestSql(tenantId, entityId, key);
    Tablet tablet = buildLatestTablet(tenantId, entityId, tsKvEntry, key);
    try (ITableSession session = tableSessionPool.getSession()) {
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
    long startTs = query.getStartTs();
    long endTs = query.getEndTs();
    // (1) Snapshot derived and overlay SEPARATELY under the per-identity lock, so the overlay
    // mutation below is bounded by the OVERLAY row's OWN ts — not the merged ts, which can come
    // from
    // the derived telemetry store (e.g. a telemetry-only / saveWithoutLatest write). BOTH reads are
    // B1-lenient: a same-ts two-type telemetry row must not block a delete, and although the
    // overlay
    // is single-typed within a JVM (per-identity lock + delete-then-insert), a clustered deployment
    // (the latest path has no cluster-mode gate) could leave a same-ts two-type overlay row, which
    // must not wedge the delete either — an ambiguous overlay is treated as absent and self-heals.
    Optional<TsKvEntry> derived = readDerivedLatestLenient(tenantId, entityId, key);
    Optional<TsKvEntry> overlay =
        readLatestRowB1Lenient(buildFindLatestOverlaySql(tenantId, entityId, key), key);
    Optional<TsKvEntry> latest = mergeLatest(derived, overlay);
    if (latest.isEmpty()) {
      return new TsKvLatestRemovingResult(key, false);
    }
    long ts = latest.get().getTs();
    if (ts < startTs || ts >= endTs) {
      // The current latest is outside the half-open [startTs, endTs) window: nothing removed, so TB
      // is not told to delete a latest value that is still valid.
      return new TsKvLatestRemovingResult(key, false);
    }
    // TB only invokes removeLatest with deleteLatest=true (BaseTimeseriesService gates it); the
    // check here is defensive — when false, do not mutate the overlay and report nothing removed.
    boolean deleteLatest = !Boolean.FALSE.equals(query.getDeleteLatest());
    if (!deleteLatest) {
      return new TsKvLatestRemovingResult(key, false);
    }
    // (2) Only an overlay row whose OWN ts is inside the window may be deleted; an overlay value
    // older than the window (e.g. when the in-window latest is a derived/telemetry-only value) must
    // SURVIVE as the next latest rather than being wiped by the tag-only all-time DELETE.
    boolean overlayInWindow =
        overlay.isPresent() && overlay.get().getTs() >= startTs && overlay.get().getTs() < endTs;
    boolean rewrite = Boolean.TRUE.equals(query.getRewriteLatestIfDeleted());
    if (rewrite) {
      // (3) Resurrect the next-older value as the new latest, looking BEFORE the window across BOTH
      // stores: telemetry (time < startTs) and the overlay row if its own ts is < startTs (the
      // overlay is single-row, so an out-of-window-older overlay value is itself a candidate);
      // max-ts-wins. Write it into the overlay and return it as data so onTimeSeriesDelete emits a
      // WS UPDATE (removed=true, getData()=prior).
      Optional<TsKvEntry> priorDerived = doFindHistoryBefore(tenantId, entityId, key, startTs);
      Optional<TsKvEntry> priorOverlay = overlay.filter(e -> e.getTs() < startTs);
      Optional<TsKvEntry> prior = mergeLatest(priorDerived, priorOverlay);
      if (prior.isPresent()) {
        // If the resurrected prior is the overlay's OWN out-of-window value (priorOverlay won the
        // max-ts merge), it is ALREADY stored and outside the delete window — do NOT rewrite it: a
        // redundant delete-then-insert would needlessly risk an out-of-window, possibly
        // latest-only,
        // value on an INSERT failure (and converges to the same result with no write). Only a prior
        // that came from the derived telemetry store is installed into the overlay; that write also
        // drops any in-window overlay row via the delete-then-insert, and is recoverable from
        // {@code
        // telemetry} if the insert fails.
        boolean priorIsExistingOverlay =
            priorOverlay.isPresent()
                && (priorDerived.isEmpty()
                    || priorOverlay.get().getTs() >= priorDerived.get().getTs());
        if (!priorIsExistingOverlay) {
          upsertOverlay(tenantId, entityId, prior.get(), key);
        }
        return new TsKvLatestRemovingResult(prior.get(), null);
      }
      // No older value anywhere to resurrect: fall through to a plain latest delete.
    }
    // (4) Plain delete: remove ONLY an in-window overlay row (the in-window derived telemetry value
    // is removed by the separate historical remove future). removed=true reports the in-window
    // latest was deleted; an out-of-window overlay value, if any, survives and resurfaces via the
    // derived merge (the documented telemetry-derived eventual-consistency residual).
    if (overlayInWindow) {
      deleteOverlay(tenantId, entityId, key);
    }
    return new TsKvLatestRemovingResult(key, true, null);
  }

  private Optional<TsKvEntry> doFindHistoryBefore(
      TenantId tenantId, EntityId entityId, String key, long startTs) throws Exception {
    // B1-lenient like the snapshot read: a same-ts two-type next-older row is treated as no
    // resurrectable prior (fall through to a plain delete) rather than failing the removeLatest.
    return readLatestRowB1Lenient(buildRewriteHistorySql(tenantId, entityId, key, startTs), key);
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
