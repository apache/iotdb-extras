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
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;
import org.thingsboard.server.dao.util.TimeUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Historical telemetry DAO for the IoTDB Table Mode backend.
 *
 * <p>Spring activation: database.ts.type=iotdb-table and iotdb.ts.experimental-raw-only=true.
 *
 * <p>Strategy F consumes ThingsBoard common-data types from the compile classpath and binds the
 * real historical {@link TimeseriesDao} SPI.
 *
 * <p>This implementation delivers the batch WRITE path ({@link #save}), the RAW (non-aggregated)
 * historical READ path, the time-bucketed aggregation READ path ({@link #findAllAsync}) and the
 * DELETE path ({@link #remove}), all driven through a bounded read thread-pool. Aggregation
 * supports BOTH the fixed-width millisecond {@code date_bin} path (buckets anchored at the query
 * start timestamp) AND the timezone-aware per-bucket calendar path ({@code WEEK}/{@code
 * WEEK_ISO}/{@code MONTH}/{@code QUARTER}), which walks calendar boundaries in Java and runs one
 * bounded aggregate query per bucket.
 */
@Slf4j
@Repository
@ConditionalOnBean(name = IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
@Conditional(IoTDBTableRawOnlyEnabledCondition.class)
public class IoTDBTableTimeseriesDao extends IoTDBTableBaseDao
    implements TimeseriesDao, DisposableBean {
  private static final long SECONDS_PER_DAY = 86400L;
  private static final String TABLE_NAME = IoTDBTableTimeseriesWriter.TABLE_NAME;

  // Result-set column aliases for the time-bucketed aggregation read path (design doc §3.3).
  private static final String BUCKET_TS_COLUMN = "bucket_ts";
  private static final String AGG_NUM_COLUMN = "agg_num";
  private static final String AGG_STR_COLUMN = "agg_str";
  private static final String MAX_TS_COLUMN = "max_ts";
  // Typed COUNT columns: one non-null counter per FIELD type, matching ThingsBoard's per-type
  // SUM(CASE WHEN <col> IS NOT NULL THEN 1 ELSE 0 END) and dominant-column selection.
  private static final String COUNT_BOOL_COLUMN = "count_bool";
  private static final String COUNT_STR_COLUMN = "count_str";
  private static final String COUNT_JSON_COLUMN = "count_json";
  private static final String COUNT_LONG_COLUMN = "count_long";
  private static final String COUNT_DOUBLE_COLUMN = "count_double";
  // Per-type SUM aliases: ThingsBoard 4.3.1.2 keeps a SUM result LONG-typed when only long values
  // participate in the bucket and promotes to DOUBLE only when a double participates, so the SUM
  // path projects both partial sums and lets the row mapper pick the TB-faithful result type.
  private static final String SUM_LONG_COLUMN = "sum_long";
  private static final String SUM_DOUBLE_COLUMN = "sum_double";
  // Direct long MIN/MAX channels: MIN/MAX over the raw long_v column SELECT a stored long value
  // with
  // no accumulation, so they are exact for every long (even > 2^53). The long-only MIN/MAX mapping
  // reads these instead of the COALESCE->DOUBLE agg_num, which would round-trip a large long
  // through
  // a double and lose precision. They are also reused as the SUM exactness-bound inputs.
  private static final String MIN_LONG_COLUMN = "min_long";
  private static final String MAX_LONG_COLUMN = "max_long";
  // Mixed-type numeric promotion: exactly one of double_v / long_v is non-null per row.
  private static final String NUMERIC_VALUE = "COALESCE(double_v, CAST(long_v AS DOUBLE))";
  // Doubles represent every integer in [-2^53, 2^53] exactly; beyond that the gap grows. IoTDB
  // computes SUM(INT64) with a DOUBLE accumulator, so a long-only SUM is only provably bit-exact
  // while every partial sum stays within this range (see aggregatedSumEntry).
  private static final long DOUBLE_EXACT_INTEGER_LIMIT = 9007199254740992L; // 2^53

  private final IoTDBTableTimeseriesWriter timeseriesWriter;
  private final long defaultTtlSeconds;

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
    int readThreads = config.getTs().getRead().getThreads();
    int flushThreads = config.getTs().getSave().getFlushThreads();
    if (readThreads + flushThreads > config.getSessionPoolSize()) {
      log.warn(
          "IoTDB Table Mode read/write workers ({}) exceed session pool size ({}); "
              + "reads or flushes may wait for sessions",
          readThreads + flushThreads,
          config.getSessionPoolSize());
    }
    initReadExecutor(
        readThreads,
        config.getTs().getRead().getQueueCapacity(),
        config.getTs().getSave().getShutdownDrainTimeoutMs(),
        "iotdb-table-timeseries-read-worker-",
        "IoTDB Table Mode timeseries read queue is full",
        "IoTDB Table Mode timeseries DAO is shutting down");
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
    shutdownReadExecutor();
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
    return readAggregatedQuery(tenantId, entityId, query);
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

  /**
   * Routes calendar {@link IntervalType}s to {@link #readCalendarAggregatedQuery} and {@code
   * MILLISECONDS}/{@code null} to {@link #readMillisecondsAggregatedQuery}.
   */
  private ReadTsKvQueryResult readAggregatedQuery(
      TenantId tenantId, EntityId entityId, ReadTsKvQuery query) throws Exception {
    if (isCalendarInterval(query)) {
      return readCalendarAggregatedQuery(tenantId, entityId, query);
    }
    return readMillisecondsAggregatedQuery(tenantId, entityId, query);
  }

  /**
   * Time-bucketed aggregation read path matching ThingsBoard 4.3.1.2's {@code
   * AbstractChunkedAggregationTimeseriesDao.findAllAsync} contract (verified against tag v4.3.1.2).
   *
   * <p>ThingsBoard walks {@code [startTs, endPeriod)} (where {@code endPeriod = max(startTs + 1,
   * endTs)}) in fixed-width {@code interval}-millisecond buckets <em>anchored at {@code
   * startTs}</em> (NOT epoch 1970), runs one aggregate per bucket, skips empty buckets, and stamps
   * each emitted entry at the bucket <em>midpoint</em> {@code bucketStart + (bucketEnd -
   * bucketStart) / 2} with {@code bucketEnd = min(bucketStart + interval, endPeriod)} (integer
   * division; the last, end-clamped bucket therefore has an earlier-than-full-width midpoint). The
   * query order and limit are ignored for aggregation: all non-empty buckets are returned in
   * ascending time order. The result's {@code lastEntryTs} is the maximum underlying data timestamp
   * across every bucket, falling back to {@code startTs} when no data matched.
   *
   * <p>The {@code startTs}-anchored buckets come from IoTDB 2.0.8 Table Mode's three-argument
   * {@code date_bin(<interval>ms, time, <startTs>)} primitive (origin = {@code startTs}); see
   * {@link #buildAggregationSql}.
   */
  private ReadTsKvQueryResult readMillisecondsAggregatedQuery(
      TenantId tenantId, EntityId entityId, ReadTsKvQuery query) throws Exception {
    String key = requireTelemetryKey(query.getKey());
    Aggregation aggregation = aggregationOf(query);
    long interval = query.getInterval();
    if (interval <= 0L) {
      throw new IllegalArgumentException(
          "IoTDB Table Mode aggregation requires a positive interval; got " + interval);
    }
    long startTs = query.getStartTs();
    // ThingsBoard's endPeriod guards a zero-width range so a single bucket is still walked.
    long endPeriod = Math.max(startTs + 1, query.getEndTs());

    String sql = buildAggregationSql(tenantId, entityId, query, aggregation, interval, endPeriod);
    List<TsKvEntry> entries = new ArrayList<>();
    long lastEntryTs = startTs;
    boolean hasEntry = false;
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        long bucketStart = row.getTimestamp(BUCKET_TS_COLUMN).getTime();
        // Clamp to endPeriod exactly like the aggregate query's `time < endPeriod` filter. The SUM
        // re-sum fallback re-queries this same [bucketStart, bucketEnd) window, so it covers
        // exactly
        // the rows date_bin aggregated; using the un-clamped bucketStart+interval would let a last,
        // end-clamped bucket re-sum rows beyond the query window.
        long bucketEnd = Math.min(bucketStart + interval, endPeriod);
        KvEntry value =
            aggregatedEntry(
                aggregation,
                row,
                new SumReSumContext(tenantId, entityId, key, bucketStart, bucketEnd));
        if (value == null) {
          // Defensive: a bucket with only NULL value columns produces no entry.
          continue;
        }
        long bucketTs = bucketStart + (bucketEnd - bucketStart) / 2;
        entries.add(new BasicTsKvEntry(bucketTs, value));
        // ThingsBoard reports MAX(ts) of the underlying data, not the bucket midpoint.
        long maxDataTs = row.getTimestamp(MAX_TS_COLUMN).getTime();
        if (!hasEntry || maxDataTs > lastEntryTs) {
          lastEntryTs = maxDataTs;
          hasEntry = true;
        }
      }
    }
    return new ReadTsKvQueryResult(query.getId(), entries, lastEntryTs);
  }

  /**
   * Calendar-interval (non-{@code MILLISECONDS}) aggregation read path matching ThingsBoard
   * 4.3.1.2's {@code AbstractChunkedAggregationTimeseriesDao.findAllAsync} contract for {@code
   * WEEK}/{@code WEEK_ISO}/{@code MONTH}/{@code QUARTER} buckets (verified against tag v4.3.1.2).
   *
   * <p>IoTDB 2.0.8's native {@code date_bin} calendar primitive cannot reproduce ThingsBoard's
   * boundaries: it anchors each calendar bucket on the <em>origin's day-of-month</em> (so {@code
   * date_bin(1mo, time, startTs)} from a mid-month {@code startTs} steps day-15 → day-15, not to
   * the 1st of each month) and it exposes <em>no timezone argument</em> (it computes in the
   * server's UTC zone only). ThingsBoard instead advances {@code startTs} to the start of the next
   * calendar unit in {@code tzId} via {@link TimeUtils#calculateIntervalEnd}, so the first bucket
   * is the partial {@code [startTs, nextCalendarBoundary)} and later buckets are full calendar
   * units. This path therefore reproduces ThingsBoard exactly the way ThingsBoard itself does: it
   * walks the calendar boundaries in Java and issues one bounded aggregate query per bucket
   * (ThingsBoard issues one future per bucket), reusing the very same projection, row mapper and
   * typed-COUNT logic as the {@code MILLISECONDS} path.
   *
   * <p>Walking {@code [startTs, endPeriod)} where {@code endPeriod = max(startTs + 1, endTs)}: each
   * iteration takes {@code bucketStart = startPeriod}, {@code bucketEnd = min(calculateIntervalEnd(
   * bucketStart, intervalType, tzId), endPeriod)}, stamps the emitted entry at the integer midpoint
   * {@code bucketStart + (bucketEnd - bucketStart) / 2}, skips empty buckets, and advances {@code
   * startPeriod = bucketEnd}. Query order and limit are ignored (aggregation always returns every
   * non-empty bucket ascending). {@code lastEntryTs} is the maximum underlying data timestamp
   * across all buckets, falling back to {@code startTs} when nothing matched.
   */
  private ReadTsKvQueryResult readCalendarAggregatedQuery(
      TenantId tenantId, EntityId entityId, ReadTsKvQuery query) throws Exception {
    String key = requireTelemetryKey(query.getKey());
    Aggregation aggregation = aggregationOf(query);
    IntervalType intervalType = query.getAggParameters().getIntervalType();
    ZoneId tzId = calendarZone(query);
    long startTs = query.getStartTs();
    // ThingsBoard clamps endPeriod = max(startTs + 1, endTs) so a zero-width range still walks one
    // bucket; the final calendar bucket is clamped to endPeriod just like the milliseconds path.
    long endPeriod = Math.max(startTs + 1, query.getEndTs());

    List<TsKvEntry> entries = new ArrayList<>();
    long lastEntryTs = startTs;
    boolean hasEntry = false;
    try (ITableSession session = tableSessionPool.getSession()) {
      long startPeriod = startTs;
      while (startPeriod < endPeriod) {
        long bucketStart = startPeriod;
        long bucketEnd =
            Math.min(TimeUtils.calculateIntervalEnd(bucketStart, intervalType, tzId), endPeriod);
        // Defensive: calculateIntervalEnd always advances, but guard against a degenerate boundary
        // (e.g. a clamp that did not move) so the loop cannot spin forever.
        if (bucketEnd <= bucketStart) {
          bucketEnd = endPeriod;
        }
        long bucketTs = bucketStart + (bucketEnd - bucketStart) / 2;
        String sql =
            buildBucketAggregationSql(tenantId, entityId, key, aggregation, bucketStart, bucketEnd);
        try (SessionDataSet dataSet = session.executeQueryStatement(sql)) {
          SessionDataSet.DataIterator row = dataSet.iterator();
          // Each calendar bucket is its own bounded aggregate query with no GROUP BY, so an EMPTY
          // window still returns one row (COUNT=0, AVG/MIN/MAX/SUM=NULL, MAX(time)=NULL). The
          // milliseconds GROUP BY path drops empty buckets implicitly; here we must skip them
          // explicitly. MAX(time) is NULL iff the window matched zero rows (time is never null), so
          // it is the robust empty-bucket test across every aggregation type, including COUNT
          // (which
          // would otherwise emit a spurious 0 and violate ThingsBoard's "skip empty buckets").
          if (row.next() && !row.isNull(MAX_TS_COLUMN)) {
            KvEntry value =
                aggregatedEntry(
                    aggregation,
                    row,
                    new SumReSumContext(tenantId, entityId, key, bucketStart, bucketEnd));
            if (value != null) {
              entries.add(new BasicTsKvEntry(bucketTs, value));
              // The enclosing guard already proved MAX_TS_COLUMN is non-null (an empty bucket was
              // skipped), so read the max underlying data timestamp directly like the ms path.
              long maxDataTs = row.getTimestamp(MAX_TS_COLUMN).getTime();
              if (!hasEntry || maxDataTs > lastEntryTs) {
                lastEntryTs = maxDataTs;
                hasEntry = true;
              }
            }
          }
        }
        startPeriod = bucketEnd;
      }
    }
    return new ReadTsKvQueryResult(query.getId(), entries, lastEntryTs);
  }

  private static boolean isCalendarInterval(ReadTsKvQuery query) {
    var params = query.getAggParameters();
    if (params == null) {
      return false;
    }
    IntervalType intervalType = params.getIntervalType();
    // A null IntervalType defaults to MILLISECONDS semantics (fixed-width date_bin bucketing).
    return intervalType != null && intervalType != IntervalType.MILLISECONDS;
  }

  private static ZoneId calendarZone(ReadTsKvQuery query) {
    ZoneId tzId = query.getAggParameters().getTzId();
    // ThingsBoard always supplies a zone for calendar aggregation (AggregationParams.calendar
    // resolves it); default to the system zone defensively so calculateIntervalEnd never NPEs.
    return tzId != null ? tzId : ZoneId.systemDefault();
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

  /**
   * Builds the {@code startTs}-anchored, time-bucketed aggregation SQL matching ThingsBoard
   * 4.3.1.2. Buckets are anchored at {@code startTs} via the three-argument {@code
   * date_bin(<interval>ms, time, <startTs>)} primitive (origin = {@code startTs}) so bucket {@code
   * k} spans {@code [startTs + k*interval, startTs + (k+1)*interval)} rather than the epoch-1970
   * alignment of the two-argument form. An explicit milliseconds literal ({@code <interval>ms})
   * avoids the {@code 1m}/{@code 1M} minute-vs-month parsing ambiguity flagged for IoTDB 2.0.x;
   * {@code ReadTsKvQuery.getInterval()} is already expressed in milliseconds.
   *
   * <p>Every projection also selects {@code MAX(time)} so the reader can report the maximum
   * underlying data timestamp as {@code lastEntryTs}. Results are ordered by the bucket key
   * ascending; the query's order and limit are intentionally ignored for aggregation (only the raw
   * {@code Aggregation.NONE} path honours them). Mixed-type numeric aggregates promote {@code
   * long_v} to DOUBLE via {@code COALESCE(double_v, CAST(long_v AS DOUBLE))}; {@code COUNT} counts
   * non-null typed values per FIELD column (see {@link #countProjection()}). {@code MIN}/{@code
   * MAX} project both a numeric and a string aggregate so the row mapper can pick the populated one
   * (IoTDB performs lexicographic MIN/MAX over STRING natively).
   */
  private String buildAggregationSql(
      TenantId tenantId,
      EntityId entityId,
      ReadTsKvQuery query,
      Aggregation aggregation,
      long interval,
      long endExclusive) {
    String key = requireTelemetryKey(query.getKey());
    String dateBin = "date_bin(" + interval + "ms, time, " + query.getStartTs() + ")";
    StringBuilder sql = new StringBuilder("SELECT ").append(dateBin).append(" AS ");
    sql.append(BUCKET_TS_COLUMN).append(", ").append(aggregationProjection(aggregation));
    sql.append(", MAX(time) AS ").append(MAX_TS_COLUMN);
    sql.append(" FROM ").append(TABLE_NAME);
    sql.append(" WHERE tenant_id=").append(sqlString(tenantId.getId().toString()));
    sql.append(" AND entity_type=").append(sqlString(entityId.getEntityType().name()));
    sql.append(" AND entity_id=").append(sqlString(entityId.getId().toString()));
    sql.append(" AND key=").append(sqlString(key));
    sql.append(" AND time >= ").append(query.getStartTs());
    // Upper bound is ThingsBoard's clamped endPeriod (max(startTs+1, endTs)), not the raw endTs, so
    // a zero-width [startTs, startTs] query still walks the single [startTs, startTs+1) bucket and
    // includes a point at startTs (matching AbstractChunkedAggregationTimeseriesDao).
    sql.append(" AND time < ").append(endExclusive);
    sql.append(" GROUP BY 1 ORDER BY 1 ASC");
    return sql.toString();
  }

  /**
   * Builds the single-bucket aggregate SQL for one calendar bucket {@code [bucketStart,
   * bucketEnd)}. Unlike {@link #buildAggregationSql}, there is no {@code date_bin}/{@code GROUP
   * BY}: ThingsBoard computes calendar bucket boundaries in Java (timezone-aware,
   * calendar-start-aligned) and runs one bounded aggregate per bucket, so the half-open {@code time
   * >= bucketStart AND time < bucketEnd} window <em>is</em> the bucket. The same aggregate
   * projection, {@code MAX(time) AS max_ts} and typed-COUNT logic as the {@code MILLISECONDS} path
   * are reused; the caller derives the bucket midpoint timestamp and skips empty buckets in Java.
   */
  private String buildBucketAggregationSql(
      TenantId tenantId,
      EntityId entityId,
      String key,
      Aggregation aggregation,
      long bucketStart,
      long bucketEnd) {
    StringBuilder sql = new StringBuilder("SELECT ").append(aggregationProjection(aggregation));
    sql.append(", MAX(time) AS ").append(MAX_TS_COLUMN);
    sql.append(" FROM ").append(TABLE_NAME);
    sql.append(" WHERE tenant_id=").append(sqlString(tenantId.getId().toString()));
    sql.append(" AND entity_type=").append(sqlString(entityId.getEntityType().name()));
    sql.append(" AND entity_id=").append(sqlString(entityId.getId().toString()));
    sql.append(" AND key=").append(sqlString(key));
    sql.append(" AND time >= ").append(bucketStart);
    sql.append(" AND time < ").append(bucketEnd);
    return sql.toString();
  }

  private static String aggregationProjection(Aggregation aggregation) {
    return switch (aggregation) {
      case AVG -> "AVG(" + NUMERIC_VALUE + ") AS " + AGG_NUM_COLUMN;
        // SUM keeps the ThingsBoard 4.3.1.2 result type: long-only buckets stay LONG, mixed buckets
        // promote to DOUBLE. Project the partial long/double sums plus the long/double non-null
        // counts so the row mapper can pick the type without re-reading the raw rows.
      case SUM ->
          // IoTDB 2.0.8 computes SUM over an INT64 column with a DOUBLE accumulator and returns a
          // DOUBLE. Project the long partial as SUM(CAST(long_v AS DOUBLE)) -- a plain DOUBLE --
          // and
          // NEVER cast it back to INT64 in SQL: CAST(SUM(long_v) AS INT64) THROWS a "Double value
          // out of range of long value" error at the IoTDB level when the long-only sum exceeds
          // Long.MAX, which would fail the whole aggregate query before the Java
          // bound-check/fallback
          // could run. The DOUBLE accumulator only keeps the sum bit-exact while every partial sum
          // stays within +/-2^53, so the row mapper reads MIN(long_v)/MAX(long_v) to bound the sum,
          // returns the DOUBLE cast back to long (lossless within the bound) for the provably-exact
          // long-only case, and falls back to an exact Java re-sum when the bound exceeds 2^53 (see
          // aggregatedSumEntry). The double partial stays DOUBLE.
          "SUM(CAST(long_v AS DOUBLE)) AS "
              + SUM_LONG_COLUMN
              + ", SUM(double_v) AS "
              + SUM_DOUBLE_COLUMN
              + ", MIN(long_v) AS "
              + MIN_LONG_COLUMN
              + ", MAX(long_v) AS "
              + MAX_LONG_COLUMN
              + ", "
              + numericCountProjection();
      case COUNT -> countProjection();
        // MIN/MAX keep the mixed/double numeric value via MIN/MAX(NUMERIC_VALUE) (the COALESCE
        // promotes long_v to DOUBLE, correct for mixed and double-only buckets) and the string
        // fallback via MIN/MAX(str_v). A long-only bucket instead reads the direct MIN(long_v)/
        // MAX(long_v) channel, which SELECTs a stored long with no accumulation and is therefore
        // exact for every long (even > 2^53) -- routing it through agg_num's DOUBLE would
        // round-trip
        // a large long and lose precision. The long/double non-null counts pick the populated
        // channel.
      case MIN ->
          "MIN("
              + NUMERIC_VALUE
              + ") AS "
              + AGG_NUM_COLUMN
              + ", MIN(long_v) AS "
              + MIN_LONG_COLUMN
              + ", MIN(str_v) AS "
              + AGG_STR_COLUMN
              + ", "
              + numericCountProjection();
      case MAX ->
          "MAX("
              + NUMERIC_VALUE
              + ") AS "
              + AGG_NUM_COLUMN
              + ", MAX(long_v) AS "
              + MAX_LONG_COLUMN
              + ", MAX(str_v) AS "
              + AGG_STR_COLUMN
              + ", "
              + numericCountProjection();
      case NONE ->
          throw new IllegalArgumentException("Aggregation.NONE has no aggregation projection");
    };
  }

  /**
   * Projects the long/double non-null counters that the SUM and MIN/MAX row mappers use to decide
   * the ThingsBoard-faithful result type: a bucket with only long values ({@code count_long > 0 &&
   * count_double == 0}) yields a {@code LongDataEntry}; any participating double yields a {@code
   * DoubleDataEntry}. The schema stores {@code long_v} XOR {@code double_v} per row, so these
   * counters cleanly partition the numeric rows.
   */
  private static String numericCountProjection() {
    return typedCount("long_v", COUNT_LONG_COLUMN)
        + ", "
        + typedCount("double_v", COUNT_DOUBLE_COLUMN);
  }

  /**
   * ThingsBoard's COUNT counts non-null <em>typed</em> values per FIELD column ({@code SUM(CASE
   * WHEN <col> IS NOT NULL THEN 1 ELSE 0 END)}) rather than {@code COUNT(*)}, then reports the
   * first non-zero counter in dominant-column priority order (boolean, string, json, then
   * long+double). Each per-type SUM is cast to {@code INT64} because IoTDB returns the {@code CASE}
   * sum as DOUBLE.
   */
  private static String countProjection() {
    return typedCount("bool_v", COUNT_BOOL_COLUMN)
        + ", "
        + typedCount("str_v", COUNT_STR_COLUMN)
        + ", "
        + typedCount("json_v", COUNT_JSON_COLUMN)
        + ", "
        + typedCount("long_v", COUNT_LONG_COLUMN)
        + ", "
        + typedCount("double_v", COUNT_DOUBLE_COLUMN);
  }

  private static String typedCount(String column, String alias) {
    return "CAST(SUM(CASE WHEN " + column + " IS NOT NULL THEN 1 ELSE 0 END) AS INT64) AS " + alias;
  }

  private KvEntry aggregatedEntry(
      Aggregation aggregation, SessionDataSet.DataIterator row, SumReSumContext sumContext)
      throws Exception {
    String key = sumContext.key();
    return switch (aggregation) {
      case AVG -> {
        // AVG is always DOUBLE in ThingsBoard, regardless of the participating value types.
        if (row.isNull(AGG_NUM_COLUMN)) {
          yield null;
        }
        yield new DoubleDataEntry(key, row.getDouble(AGG_NUM_COLUMN));
      }
      case SUM -> aggregatedSumEntry(row, sumContext);
      case COUNT -> new LongDataEntry(key, typedCount(row));
      case MIN, MAX -> {
        long countLong = countColumn(row, COUNT_LONG_COLUMN);
        long countDouble = countColumn(row, COUNT_DOUBLE_COLUMN);
        if (countLong > 0L && countDouble == 0L) {
          // Long-only bucket: read the direct MIN(long_v)/MAX(long_v) channel, which is exact for
          // every long (MIN/MAX SELECT a stored value with no accumulation). Routing it through
          // agg_num's COALESCE->DOUBLE would round a long > 2^53 down to the nearest double; this
          // keeps the LONG result bit-exact, matching ThingsBoard 4.3.1.2.
          String longColumn = aggregation == Aggregation.MIN ? MIN_LONG_COLUMN : MAX_LONG_COLUMN;
          if (!row.isNull(longColumn)) {
            yield new LongDataEntry(key, row.getLong(longColumn));
          }
        }
        if (!row.isNull(AGG_NUM_COLUMN)) {
          // Any participating double promotes the result to DOUBLE; the mixed/double-only value is
          // byte-for-byte unchanged from the prior DoubleDataEntry behaviour.
          yield new DoubleDataEntry(key, row.getDouble(AGG_NUM_COLUMN));
        }
        if (!row.isNull(AGG_STR_COLUMN)) {
          yield new StringDataEntry(key, row.getString(AGG_STR_COLUMN));
        }
        yield null;
      }
      case NONE -> throw new IllegalArgumentException("Aggregation.NONE is not an aggregate");
    };
  }

  /**
   * Maps a SUM bucket to its ThingsBoard-faithful entry. A mixed/double bucket promotes to DOUBLE
   * (unchanged). A long-only bucket keeps the LONG type but must be EXACT: IoTDB computes {@code
   * SUM(long_v)} with a DOUBLE accumulator (projected here as {@code SUM(CAST(long_v AS DOUBLE))}
   * to avoid the INT64-cast overflow error), so the double sum is only bit-exact while every
   * partial sum stays within {@code +/-2^53}. Because {@code |sum| <= count_long * maxAbs} and
   * every partial sum is bounded the same way (where {@code maxAbs = max(|min_long|,|max_long|)}),
   * the double sum is provably exact iff {@code count_long * maxAbs <= 2^53}. When that bound may
   * be exceeded we cannot trust the double sum, so we re-query the bucket's raw {@code long_v}
   * values and accumulate them in Java as {@code long} (natural overflow to 2^63, matching
   * ThingsBoard's long arithmetic). The fast SQL path handles every normal bucket; only genuinely
   * huge buckets re-query.
   */
  private KvEntry aggregatedSumEntry(SessionDataSet.DataIterator row, SumReSumContext sumContext)
      throws Exception {
    String key = sumContext.key();
    long countLong = countColumn(row, COUNT_LONG_COLUMN);
    long countDouble = countColumn(row, COUNT_DOUBLE_COLUMN);
    if (countLong == 0L && countDouble == 0L) {
      // No numeric rows in the bucket: emit nothing (matches the prior NULL-agg behaviour).
      return null;
    }
    // The long partial is projected as SUM(CAST(long_v AS DOUBLE)) -- a DOUBLE -- so it never
    // throws
    // the INT64 out-of-range error a SQL CAST would (see aggregationProjection); read it as a
    // double.
    double sumLong = nullableDouble(row, SUM_LONG_COLUMN);
    if (countDouble > 0L) {
      // Mixed (or double-only) bucket: ThingsBoard promotes to DOUBLE, summing the long and double
      // partials together (the long partial is 0 for a double-only bucket); both partials are
      // already doubles.
      return new DoubleDataEntry(key, nullableDouble(row, SUM_DOUBLE_COLUMN) + sumLong);
    }
    // Long-only bucket: ThingsBoard keeps the SUM LONG-typed and sums longs EXACTLY.
    long minLong = nullableLong(row, MIN_LONG_COLUMN);
    long maxLong = nullableLong(row, MAX_LONG_COLUMN);
    if (sumIsProvablyExact(countLong, minLong, maxLong)) {
      // The double accumulator never lost a bit and the bound guarantees the sum is an exact
      // integer
      // in [-2^53, 2^53], so casting the DOUBLE partial back to long is lossless.
      return new LongDataEntry(key, (long) sumLong);
    }
    // The bound may exceed 2^53, so the DOUBLE accumulator may have rounded: recompute exactly.
    return new LongDataEntry(key, exactLongSum(sumContext));
  }

  /**
   * Conservative, overflow-free exactness check for a long-only {@code SUM(long_v)} computed by
   * IoTDB's DOUBLE accumulator. With {@code maxAbs = max(|min_long|, |max_long|)}, the final sum
   * and every partial sum satisfy {@code |partial| <= count_long * maxAbs}; if that product is
   * {@code <= 2^53} the accumulator stayed in double's exact-integer range and never lost a bit.
   * The product is tested via DIVISION ({@code count_long <= 2^53 / maxAbs}) so the check itself
   * cannot overflow. If {@code min_long == Long.MIN_VALUE} its absolute value is not representable
   * as a positive long, so we conservatively treat the bound as exceeded and fall back to the exact
   * Java re-sum.
   */
  private static boolean sumIsProvablyExact(long countLong, long minLong, long maxLong) {
    if (minLong == Long.MIN_VALUE) {
      // |Long.MIN_VALUE| overflows a positive long; cannot prove exactness, force the Java re-sum.
      return false;
    }
    long maxAbs = Math.max(Math.abs(minLong), Math.abs(maxLong));
    if (maxAbs == 0L) {
      // Every value is 0, so the sum is exactly 0.
      return true;
    }
    // No multiplication: count_long * maxAbs <= 2^53  <=>  count_long <= 2^53 / maxAbs.
    return countLong <= DOUBLE_EXACT_INTEGER_LIMIT / maxAbs;
  }

  /**
   * Re-queries a long-only bucket's raw {@code long_v} values and accumulates them in Java as
   * {@code long}, with natural overflow to 2^63 exactly the way ThingsBoard sums longs. Used only
   * when the bucket's values are large enough that IoTDB's DOUBLE SUM accumulator may have lost
   * precision (see {@link #sumIsProvablyExact}); the bounded window {@code [bucketStart,
   * bucketEnd)} reuses the same tenant/entity/key identity predicate as the aggregate query, on its
   * own pooled {@link ITableSession} so it never opens a second result set on the session that is
   * iterating the outer aggregate.
   */
  private long exactLongSum(SumReSumContext sumContext) throws Exception {
    String sql =
        "SELECT long_v FROM "
            + TABLE_NAME
            + " WHERE tenant_id="
            + sqlString(sumContext.tenantId().getId().toString())
            + " AND entity_type="
            + sqlString(sumContext.entityId().getEntityType().name())
            + " AND entity_id="
            + sqlString(sumContext.entityId().getId().toString())
            + " AND key="
            + sqlString(sumContext.key())
            + " AND time >= "
            + sumContext.bucketStart()
            + " AND time < "
            + sumContext.bucketEnd()
            + " AND long_v IS NOT NULL";
    long total = 0L;
    // Use a SEPARATE pooled session rather than the one iterating the outer aggregate result set:
    // IoTDB Table Mode does not guarantee two concurrently open result sets on a single session, so
    // re-using it could throw or silently close the outer result set and corrupt the remaining
    // bucket rows. The re-sum is a rare fallback, so the extra pool checkout is negligible.
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        if (!row.isNull("long_v")) {
          total += row.getLong("long_v");
        }
      }
    }
    return total;
  }

  private static long nullableLong(SessionDataSet.DataIterator row, String column)
      throws Exception {
    return row.isNull(column) ? 0L : row.getLong(column);
  }

  private static double nullableDouble(SessionDataSet.DataIterator row, String column)
      throws Exception {
    return row.isNull(column) ? 0.0D : row.getDouble(column);
  }

  /**
   * Selects ThingsBoard's dominant typed COUNT for a bucket: the first non-zero per-type counter in
   * priority order boolean, string, json, then the long+double numeric pair (a numeric value lands
   * in exactly one of {@code long_v}/{@code double_v}, so summing both yields the numeric row
   * count). For our normal single-typed-column rows this equals the row count; the priority only
   * matters for multi-typed (stale) buckets.
   */
  private static long typedCount(SessionDataSet.DataIterator row) throws Exception {
    long countBool = countColumn(row, COUNT_BOOL_COLUMN);
    if (countBool > 0L) {
      return countBool;
    }
    long countStr = countColumn(row, COUNT_STR_COLUMN);
    if (countStr > 0L) {
      return countStr;
    }
    long countJson = countColumn(row, COUNT_JSON_COLUMN);
    if (countJson > 0L) {
      return countJson;
    }
    return countColumn(row, COUNT_LONG_COLUMN) + countColumn(row, COUNT_DOUBLE_COLUMN);
  }

  private static long countColumn(SessionDataSet.DataIterator row, String column) throws Exception {
    return row.isNull(column) ? 0L : row.getLong(column);
  }

  /**
   * Carries the identity and bucket bounds a long-only SUM bucket needs to re-query its raw {@code
   * long_v} values for an exact Java re-sum when the IoTDB DOUBLE accumulator may have lost
   * precision (see {@link #aggregatedSumEntry}); {@link #exactLongSum} runs that re-query on its
   * own pooled session. The same instance also supplies the telemetry {@code key} every aggregation
   * mapping stamps onto its emitted {@link KvEntry}.
   */
  private record SumReSumContext(
      TenantId tenantId, EntityId entityId, String key, long bucketStart, long bucketEnd) {}

  private static String sqlOrder(String order) {
    String normalized = Objects.requireNonNull(order, "order").trim().toUpperCase(Locale.ROOT);
    if (!"ASC".equals(normalized) && !"DESC".equals(normalized)) {
      throw new IllegalArgumentException("Unsupported IoTDB Table Mode read order: " + order);
    }
    return normalized;
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
}
