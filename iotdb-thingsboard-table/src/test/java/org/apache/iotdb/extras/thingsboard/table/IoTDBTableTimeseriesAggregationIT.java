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
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.AggregationParams;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Docker integration test that proves the IoTDB 2.0.8 Table Mode native three-argument {@code
 * date_bin(<interval>ms, time, <startTs>)} + {@code GROUP BY} time-bucketed aggregation path
 * matches ThingsBoard 4.3.1.2's contract: buckets anchored at {@code startTs} (not epoch 1970),
 * entries stamped at the bucket midpoint, every non-empty bucket returned ascending regardless of
 * query order/limit, and typed COUNT semantics -- all against hand-computed expected values. Reuses
 * the testcontainer harness from {@link IoTDBTableTimeseriesDaoIT}: {@code
 * apache/iotdb:2.0.8-standalone}, {@code dn_rpc_address=0.0.0.0}, exposed port 6667, short-prefix
 * unique database, schema bootstrap from {@code schema-iotdb-table.sql}.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class IoTDBTableTimeseriesAggregationIT {
  private static final int FUTURE_TIMEOUT_SECONDS = 30;
  private static final Duration IOTDB_STARTUP_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IOTDB_READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration IOTDB_READY_POLL_INTERVAL = Duration.ofMillis(500);
  private static final long INTERVAL = 1000L;

  @Container
  static final GenericContainer<?> IOTDB =
      new GenericContainer<>(DockerImageName.parse("apache/iotdb:2.0.8-standalone"))
          .withExposedPorts(6667)
          .withEnv("dn_rpc_address", "0.0.0.0")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(IOTDB_STARTUP_TIMEOUT));

  @Test
  void numericAggregationsBucketCorrectlyAgainstHandComputedValues() throws Exception {
    TestScope scope =
        scope(
            "agg_numeric",
            "55555555-5555-5555-5555-555555555501",
            "66666666-6666-6666-6666-666666666601");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // Query [1000,3000), interval 1000, startTs-anchored buckets (origin=1000):
        //   Bucket [1000,2000): 10, 20, 5.5 -> midpoint 1500; sum 35.5, count 3, avg 11.8333,
        //       min 5.5, max 20.0
        //   Bucket [2000,3000): 30, 40      -> midpoint 2500; sum 70.0, count 2, avg 35.0,
        //       min 30.0, max 40.0
        //   Bucket [3000,4000): empty       -> no entry emitted
        // lastEntryTs = MAX(underlying time) = 2700 (ThingsBoard reports the max data ts).
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "n", DataType.LONG, 10L),
                entry(1200L, "n", DataType.LONG, 20L),
                entry(1500L, "n", DataType.DOUBLE, 5.5D),
                entry(2100L, "n", DataType.LONG, 30L),
                entry(2700L, "n", DataType.LONG, 40L)));

        // Result TYPE: bucket [1000,2000) is MIXED (has the 5.5 double) so SUM/MIN/MAX stay DOUBLE;
        // bucket [2000,3000) is LONG-ONLY (30, 40) so SUM/MIN/MAX come back LONG-typed (TB 4.3.1.2
        // keeps a long-only SUM/MIN/MAX LONG). AVG is always DOUBLE; COUNT is always LONG.
        ReadTsKvQueryResult avg = aggregate(dao, scope, "n", Aggregation.AVG);
        assertDoubleBuckets(
            avg, new long[] {1500L, 2500L}, new double[] {35.5D / 3D, 35.0D}, 2700L);

        ReadTsKvQueryResult sum = aggregate(dao, scope, "n", Aggregation.SUM);
        assertNumericBuckets(
            sum,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.DOUBLE, DataType.LONG},
            new double[] {35.5D, 70.0D},
            2700L);

        ReadTsKvQueryResult count = aggregate(dao, scope, "n", Aggregation.COUNT);
        assertLongBuckets(count, new long[] {1500L, 2500L}, new long[] {3L, 2L});

        ReadTsKvQueryResult min = aggregate(dao, scope, "n", Aggregation.MIN);
        assertNumericBuckets(
            min,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.DOUBLE, DataType.LONG},
            new double[] {5.5D, 30.0D},
            2700L);

        ReadTsKvQueryResult max = aggregate(dao, scope, "n", Aggregation.MAX);
        assertNumericBuckets(
            max,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.DOUBLE, DataType.LONG},
            new double[] {20.0D, 40.0D},
            2700L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void longOnlyAndMixedBucketsKeepThingsBoardResultTypeAgainstRealIoTDB() throws Exception {
    TestScope scope =
        scope(
            "agg_resulttype",
            "55555555-5555-5555-5555-555555555509",
            "66666666-6666-6666-6666-666666666609");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // End-to-end proof of the result TYPE contract against REAL IoTDB:
        //   Bucket [1000,2000): LONG-ONLY data 4,6,10 -> midpoint 1500;
        //       sum 20, min 4, max 10 -- all LONG-typed (TB 4.3.1.2 keeps a long-only result LONG).
        //   Bucket [2000,3000): MIXED data 3 (long) + 2.5 (double) -> midpoint 2500;
        //       sum 5.5, min 2.5, max 3.0 -- all DOUBLE-typed (a participating double promotes).
        // AVG is always DOUBLE; COUNT is always LONG.
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "m", DataType.LONG, 4L),
                entry(1300L, "m", DataType.LONG, 6L),
                entry(1700L, "m", DataType.LONG, 10L),
                entry(2100L, "m", DataType.LONG, 3L),
                entry(2400L, "m", DataType.DOUBLE, 2.5D)));

        ReadTsKvQueryResult sum = aggregate(dao, scope, "m", Aggregation.SUM);
        assertNumericBuckets(
            sum,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.LONG, DataType.DOUBLE},
            new double[] {20.0D, 5.5D},
            2400L);

        ReadTsKvQueryResult min = aggregate(dao, scope, "m", Aggregation.MIN);
        assertNumericBuckets(
            min,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.LONG, DataType.DOUBLE},
            new double[] {4.0D, 2.5D},
            2400L);

        ReadTsKvQueryResult max = aggregate(dao, scope, "m", Aggregation.MAX);
        assertNumericBuckets(
            max,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.LONG, DataType.DOUBLE},
            new double[] {10.0D, 3.0D},
            2400L);

        // AVG stays DOUBLE even for the long-only bucket; COUNT stays LONG everywhere.
        ReadTsKvQueryResult avg = aggregate(dao, scope, "m", Aggregation.AVG);
        assertDoubleBuckets(
            avg, new long[] {1500L, 2500L}, new double[] {20.0D / 3D, 2.75D}, 2400L);

        ReadTsKvQueryResult count = aggregate(dao, scope, "m", Aggregation.COUNT);
        assertLongBuckets(count, new long[] {1500L, 2500L}, new long[] {3L, 2L});
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void sumLongOnlyExceedingLongMaxDoesNotCrashAndReSumsExactlyAgainstRealIoTDB() throws Exception {
    TestScope scope =
        scope(
            "agg_overflow",
            "55555555-5555-5555-5555-555555555511",
            "66666666-6666-6666-6666-666666666611");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // End-to-end proof the unsafe INT64-cast path is GONE. Two longs near Long.MAX land in ONE
        // long-only bucket [1000,2000); their true sum 18446744073709550000 exceeds Long.MAX
        // (9223372036854775807). Against REAL IoTDB the old projection CAST(SUM(long_v) AS INT64)
        // THROWS "Double value ... out of range of long value" and FAILS the whole aggregate query
        // before the Java bound-check/fallback can run. The new SUM(CAST(long_v AS DOUBLE)) partial
        // never throws, the bound (count_long=2, maxAbs ~ 9.2e18 -> 2 > 2^53/maxAbs) forces the
        // exact
        // Java re-sum, and the DAO returns the bucket with the EXACT long value -- the same natural
        // 2^64 overflow ThingsBoard's long arithmetic produces: -1616.
        long nearMax = 9223372036854775000L; // sum of two exceeds Long.MAX
        long overflowSum = nearMax + nearMax; // -1616 under Java natural long overflow (== TB)
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "o", DataType.LONG, nearMax),
                entry(1500L, "o", DataType.LONG, nearMax)));

        ReadTsKvQuery query =
            new BaseReadTsKvQuery("o", 1000L, 2000L, INTERVAL, 100, Aggregation.SUM, "ASC");
        ReadTsKvQueryResult sum =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);

        // The query did NOT crash (it returned), and the long-only bucket comes back at midpoint
        // 1500
        // with the EXACT Java re-sum (-1616), proving the fallback handled the > Long.MAX sum.
        assertEquals(1, sum.getData().size(), "exactly one long-only bucket returned (no crash)");
        assertExactNumericBuckets(
            sum,
            new long[] {1500L},
            new DataType[] {DataType.LONG},
            new long[] {overflowSum},
            new double[] {0D},
            1500L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void longAggregationsStayBitExactAbove2Pow53AgainstRealIoTDB() throws Exception {
    TestScope scope =
        scope(
            "agg_precision",
            "55555555-5555-5555-5555-555555555510",
            "66666666-6666-6666-6666-666666666610");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // End-to-end PRECISION proof against REAL IoTDB for the two > 2^53 cases. Query
        // [1000,5000), interval 1000, three non-empty buckets:
        //   Bucket [1000,2000): LONG-ONLY 9007199254740993 (= 2^53 + 1) and 1000 -> midpoint 1500.
        //       MIN = 1000, MAX = 9007199254740993 (EXACT: routed through MIN(long_v)/MAX(long_v),
        //       NOT the COALESCE->DOUBLE agg_num which would round MAX to ...992). SUM =
        //       9007199254741993 (EXACT: the bound exceeds 2^53 so the DAO re-sums the raw long_v
        // in
        //       Java; IoTDB's DOUBLE SUM accumulator would have returned ...992).
        //   Bucket [2000,3000): LONG-ONLY small 4, 6 -> midpoint 2500. Fast path (bound <= 2^53):
        //       SUM = 10, MIN = 4, MAX = 6, all EXACT LONG.
        //   Bucket [3000,4000): MIXED 3 (long) + 2.5 (double) -> midpoint 3500. DOUBLE everywhere:
        //       SUM = 5.5, MIN = 2.5, MAX = 3.0 (unchanged mixed behaviour).
        long big = 9007199254740993L; // 2^53 + 1, NOT representable as a double
        long bigSum = 9007199254741993L; // big + 1000, exact long arithmetic
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "p", DataType.LONG, big),
                entry(1500L, "p", DataType.LONG, 1000L),
                entry(2100L, "p", DataType.LONG, 4L),
                entry(2400L, "p", DataType.LONG, 6L),
                entry(3100L, "p", DataType.LONG, 3L),
                entry(3400L, "p", DataType.DOUBLE, 2.5D)));

        // MIN: long-only buckets EXACT (incl. the > 2^53 MAX channel); mixed bucket DOUBLE.
        ReadTsKvQueryResult min = precisionAggregate(dao, scope, "p", Aggregation.MIN);
        assertExactNumericBuckets(
            min,
            new long[] {1500L, 2500L, 3500L},
            new DataType[] {DataType.LONG, DataType.LONG, DataType.DOUBLE},
            new long[] {1000L, 4L, 0L},
            new double[] {0D, 0D, 2.5D},
            3400L);

        // MAX: the long-only > 2^53 value comes back EXACT (9007199254740993, not ...992).
        ReadTsKvQueryResult max = precisionAggregate(dao, scope, "p", Aggregation.MAX);
        assertExactNumericBuckets(
            max,
            new long[] {1500L, 2500L, 3500L},
            new DataType[] {DataType.LONG, DataType.LONG, DataType.DOUBLE},
            new long[] {big, 6L, 0L},
            new double[] {0D, 0D, 3.0D},
            3400L);

        // SUM: the > 2^53 long-only bucket re-sums EXACTLY (9007199254741993, not ...992); the
        // small
        // long-only bucket takes the fast path; the mixed bucket promotes to DOUBLE.
        ReadTsKvQueryResult sum = precisionAggregate(dao, scope, "p", Aggregation.SUM);
        assertExactNumericBuckets(
            sum,
            new long[] {1500L, 2500L, 3500L},
            new DataType[] {DataType.LONG, DataType.LONG, DataType.DOUBLE},
            new long[] {bigSum, 10L, 0L},
            new double[] {0D, 0D, 5.5D},
            3400L);

        // AVG stays DOUBLE; COUNT stays LONG (unchanged).
        ReadTsKvQueryResult count = precisionAggregate(dao, scope, "p", Aggregation.COUNT);
        assertLongBuckets(count, new long[] {1500L, 2500L, 3500L}, new long[] {2L, 2L, 2L});
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void stringMinMaxBucketLexicographically() throws Exception {
    TestScope scope =
        scope(
            "agg_string",
            "55555555-5555-5555-5555-555555555502",
            "66666666-6666-6666-6666-666666666602");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // startTs-anchored buckets (origin=1000), midpoints 1500 / 2500:
        //   Bucket [1000,2000): 'banana','apple' -> midpoint 1500; min 'apple', max 'banana'
        //   Bucket [2000,3000): 'cherry'         -> midpoint 2500; min/max 'cherry'
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "s", DataType.STRING, "banana"),
                entry(1200L, "s", DataType.STRING, "apple"),
                entry(2100L, "s", DataType.STRING, "cherry")));

        ReadTsKvQueryResult min = aggregate(dao, scope, "s", Aggregation.MIN);
        assertStringBuckets(min, new long[] {1500L, 2500L}, new String[] {"apple", "cherry"});

        ReadTsKvQueryResult max = aggregate(dao, scope, "s", Aggregation.MAX);
        assertStringBuckets(max, new long[] {1500L, 2500L}, new String[] {"banana", "cherry"});
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void aggregationIgnoresLimitOrderAndReturnsAllBucketsAscending() throws Exception {
    TestScope scope =
        scope(
            "agg_limit",
            "55555555-5555-5555-5555-555555555503",
            "66666666-6666-6666-6666-666666666603");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(6);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // Query [1000,5000), interval 1000, startTs-anchored buckets (origin=1000):
        //   [1000,2000) data 1000 -> midpoint 1500
        //   [2000,3000) data 2000 -> midpoint 2500
        //   [3000,4000) empty     -> skipped
        //   [4000,5000) data 4000 -> midpoint 4500
        // ThingsBoard ignores query order/limit for aggregation: every non-empty bucket is
        // returned in ascending time order regardless of LIMIT 2 / ASC-vs-DESC.
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "n", DataType.LONG, 1L),
                entry(2000L, "n", DataType.LONG, 2L),
                entry(4000L, "n", DataType.LONG, 4L)));

        long[] expectedTs = {1500L, 2500L, 4500L};
        // Long-only data -> the SUM result is LONG-typed in every bucket.
        DataType[] expectedTypes = {DataType.LONG, DataType.LONG, DataType.LONG};
        double[] expectedValues = {1.0D, 2.0D, 4.0D};
        long expectedLastTs = 4000L; // MAX(underlying time)

        // ASC + LIMIT 2: limit and order are ignored; all three buckets come back ascending.
        ReadTsKvQuery asc =
            new BaseReadTsKvQuery("n", 1000L, 5000L, INTERVAL, 2, Aggregation.SUM, "ASC");
        ReadTsKvQueryResult ascResult =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(asc))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);
        assertNumericBuckets(ascResult, expectedTs, expectedTypes, expectedValues, expectedLastTs);

        // DESC + LIMIT 2: identical result -- order and limit have no effect on aggregation.
        ReadTsKvQuery desc =
            new BaseReadTsKvQuery("n", 1000L, 5000L, INTERVAL, 2, Aggregation.SUM, "DESC");
        ReadTsKvQueryResult descResult =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(desc))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);
        assertNumericBuckets(descResult, expectedTs, expectedTypes, expectedValues, expectedLastTs);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void nonAlignedStartTsBucketsAnchorAtStartTsNotEpochZero() throws Exception {
    TestScope scope =
        scope(
            "agg_nonalign",
            "55555555-5555-5555-5555-555555555505",
            "66666666-6666-6666-6666-666666666605");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // startTs=1001 is NOT a multiple of interval=1000. With epoch-0 (2-arg date_bin) the point
        // at 2000 would land in bucket [2000,3000); with startTs-anchored buckets (3-arg origin)
        // it lands in [1001,2001) -> midpoint 1001 + (2001-1001)/2 = 1501. A second point at 2500
        // lands in [2001,3001) -> midpoint 2501. This proves epoch-0 misalignment is gone.
        saveAll(
            dao,
            scope,
            List.of(entry(2000L, "n", DataType.LONG, 7L), entry(2500L, "n", DataType.LONG, 9L)));

        ReadTsKvQuery query =
            new BaseReadTsKvQuery("n", 1001L, 3001L, INTERVAL, 100, Aggregation.SUM, "ASC");
        ReadTsKvQueryResult result =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);

        // Bucket [1001,2001) midpoint 1501 holds 7; bucket [2001,3001) midpoint 2501 holds 9.
        // Long-only data -> LONG-typed SUM in both buckets.
        assertNumericBuckets(
            result,
            new long[] {1501L, 2501L},
            new DataType[] {DataType.LONG, DataType.LONG},
            new double[] {7.0D, 9.0D},
            2500L);

        // COUNT on the same non-aligned range yields the same midpoints and counts of 1 each.
        ReadTsKvQuery countQuery =
            new BaseReadTsKvQuery("n", 1001L, 3001L, INTERVAL, 100, Aggregation.COUNT, "ASC");
        ReadTsKvQueryResult countResult =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(countQuery))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);
        assertLongBuckets(countResult, new long[] {1501L, 2501L}, new long[] {1L, 1L});
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void rawPathStillWorksWhenAggregationIsNone() throws Exception {
    TestScope scope =
        scope(
            "agg_none",
            "55555555-5555-5555-5555-555555555504",
            "66666666-6666-6666-6666-666666666604");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(3);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "n", DataType.LONG, 7L),
                entry(1500L, "n", DataType.LONG, 8L),
                entry(2100L, "n", DataType.LONG, 9L)));

        ReadTsKvQuery raw = new BaseReadTsKvQuery("n", 1000L, 3000L, 10, "ASC");
        ReadTsKvQueryResult rawResult =
            dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(raw))
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .get(0);

        // Aggregation.NONE returns every raw row (not bucketed) at its own timestamp.
        assertEquals(3, rawResult.getData().size());
        assertEquals(1000L, rawResult.getData().get(0).getTs());
        assertEquals(1500L, rawResult.getData().get(1).getTs());
        assertEquals(2100L, rawResult.getData().get(2).getTs());
        assertEquals(9L, rawResult.getData().get(2).getValue());
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void calendarMonthBucketsLandOnTbFaithfulCalendarBoundariesWithMidpoints() throws Exception {
    TestScope scope =
        scope(
            "agg_month",
            "55555555-5555-5555-5555-555555555506",
            "66666666-6666-6666-6666-666666666606");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // UTC calendar MONTH buckets for [2023-01-01, 2023-04-01): three variable-width months
        //   Jan [1672531200000,1675209600000) 31d -> midpoint 1673870400000 (2023-01-16T12:00Z)
        //   Feb [1675209600000,1677628800000) 28d -> midpoint 1676419200000 (2023-02-15T00:00Z)
        //   Mar [1677628800000,1680307200000) 31d -> midpoint 1678968000000 (2023-03-16T12:00Z)
        // The differing midpoints (16th-noon vs 15th-midnight) prove TRUE calendar widths, not a
        // fixed 30-day step; the Java-side bucketing reproduces TimeUtils.calculateIntervalEnd.
        saveAll(
            dao,
            scope,
            List.of(
                entry(1673308800000L, "n", DataType.LONG, 10L), // 2023-01-10
                entry(1674172800000L, "n", DataType.LONG, 20L), // 2023-01-20
                entry(1676419200000L, "n", DataType.LONG, 100L), // 2023-02-15
                entry(1677974400000L, "n", DataType.DOUBLE, 5.0D), // 2023-03-05
                entry(1679702400000L, "n", DataType.DOUBLE, 7.0D))); // 2023-03-25

        long startTs = 1672531200000L; // 2023-01-01T00:00Z
        long endTs = 1680307200000L; // 2023-04-01T00:00Z
        long[] midpoints = {1673870400000L, 1676419200000L, 1678968000000L};

        // Result TYPE per calendar bucket: Jan (10,20) and Feb (100) are LONG-only -> LONG SUM/MAX;
        // Mar (5.0, 7.0) is double-only -> DOUBLE SUM/MAX. AVG is always DOUBLE; COUNT LONG.
        ReadTsKvQueryResult sum =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.SUM);
        // Jan SUM=30, Feb SUM=100, Mar SUM=12; lastEntryTs = MAX(data ts) = 2023-03-25.
        assertNumericBuckets(
            sum,
            midpoints,
            new DataType[] {DataType.LONG, DataType.LONG, DataType.DOUBLE},
            new double[] {30.0D, 100.0D, 12.0D},
            1679702400000L);

        ReadTsKvQueryResult count =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.COUNT);
        assertLongBuckets(count, midpoints, new long[] {2L, 1L, 2L});

        ReadTsKvQueryResult avg =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.AVG);
        assertDoubleBuckets(avg, midpoints, new double[] {15.0D, 100.0D, 6.0D}, 1679702400000L);

        ReadTsKvQueryResult max =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.MAX);
        assertNumericBuckets(
            max,
            midpoints,
            new DataType[] {DataType.LONG, DataType.LONG, DataType.DOUBLE},
            new double[] {20.0D, 100.0D, 7.0D},
            1679702400000L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void calendarMonthFirstBucketIsPartialFromMidMonthStart() throws Exception {
    TestScope scope =
        scope(
            "agg_partial",
            "55555555-5555-5555-5555-555555555507",
            "66666666-6666-6666-6666-666666666607");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // startTs = 2023-01-15 (NOT a month boundary). ThingsBoard advances to the next calendar
        // boundary (Feb 1), so the FIRST bucket is the partial [Jan15, Feb1) with midpoint
        // 1674475200000 (2023-01-23T12:00Z), then the full calendar month [Feb1, Mar1).
        saveAll(
            dao,
            scope,
            List.of(
                entry(1673740800000L, "n", DataType.LONG, 3L), // 2023-01-15 (bucket start)
                entry(1676419200000L, "n", DataType.LONG, 9L))); // 2023-02-15

        // Long-only data -> LONG-typed SUM in both the partial and full calendar buckets.
        ReadTsKvQueryResult sum =
            calendarAggregate(dao, scope, "n", 1673740800000L, 1677628800000L, Aggregation.SUM);
        assertNumericBuckets(
            sum,
            new long[] {1674475200000L, 1676419200000L},
            new DataType[] {DataType.LONG, DataType.LONG},
            new double[] {3.0D, 9.0D},
            1676419200000L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void calendarCountSkipsEmptyMiddleMonthAgainstRealIoTDB() throws Exception {
    TestScope scope =
        scope(
            "agg_emptymid",
            "55555555-5555-5555-5555-555555555508",
            "66666666-6666-6666-6666-666666666608");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(4);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // End-to-end empty-bucket proof against REAL IoTDB: write rows in Jan and Mar 2023 but NONE
        // in Feb, then run a UTC calendar MONTH COUNT spanning all three months. For the empty Feb
        // bucket the bounded per-bucket aggregate REALLY returns one row with COUNT columns = 0 and
        // MAX(time) = NULL (the shape the unit mocks replicate via emptyAggRow). The DAO's
        // `!isNull(max_ts)` guard must skip it, so EXACTLY the two non-empty months come back with
        // their correct counts and calendar midpoints; the empty Feb month is ABSENT (not a
        // spurious
        // count-0 entry).
        //   Jan [1672531200000,1675209600000) 31d -> midpoint 1673870400000, count 2
        //   Feb [1675209600000,1677628800000) 28d -> EMPTY -> skipped (no entry)
        //   Mar [1677628800000,1680307200000) 31d -> midpoint 1678968000000, count 2
        saveAll(
            dao,
            scope,
            List.of(
                entry(1673308800000L, "n", DataType.LONG, 10L), // 2023-01-10
                entry(1674172800000L, "n", DataType.LONG, 20L), // 2023-01-20
                entry(1677974400000L, "n", DataType.LONG, 5L), // 2023-03-05
                entry(1679702400000L, "n", DataType.LONG, 7L))); // 2023-03-25

        long startTs = 1672531200000L; // 2023-01-01T00:00Z
        long endTs = 1680307200000L; // 2023-04-01T00:00Z

        ReadTsKvQueryResult count =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.COUNT);
        // EXACTLY the two non-empty months at their calendar midpoints; Feb is absent.
        assertLongBuckets(count, new long[] {1673870400000L, 1678968000000L}, new long[] {2L, 2L});
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void maxKeepsBucketsWhoseValuesAreAllNonPositiveAgainstRealIoTDB() throws Exception {
    TestScope scope =
        scope(
            "agg_nonpositive",
            "55555555-5555-5555-5555-555555555511",
            "66666666-6666-6666-6666-666666666611");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // Regression for apache/iotdb#18300 against REAL IoTDB. The server's GROUPED max
        // accumulator seeds FLOAT/DOUBLE state with Double.MIN_VALUE -- the smallest POSITIVE
        // value -- so on every release up to and including 2.0.10 a bucket whose maximum is zero
        // or negative makes MAX(<expression>) return NULL. This DAO reads a NULL aggregate as an
        // empty bucket and skips it, so such buckets used to VANISH from the series: a MAX
        // downsampling query over a sub-zero sensor silently returned fewer points, with no
        // error anywhere. The projection computes -MIN(-x) instead, which the unaffected grouped
        // MIN accumulator evaluates exactly. Every bucket here has a non-positive maximum, so on
        // the old projection this test fails with "bucket count expected 2 but was 0".
        //   Bucket [1000,2000): doubles -5.0, -3.0 -> midpoint 1500, MAX = -3.0, MIN = -5.0
        //   Bucket [2000,3000): doubles  0.0,  0.0 -> midpoint 2500, MAX =  0.0, MIN =  0.0
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "np", DataType.DOUBLE, -5.0D),
                entry(1500L, "np", DataType.DOUBLE, -3.0D),
                entry(2100L, "np", DataType.DOUBLE, 0.0D),
                entry(2400L, "np", DataType.DOUBLE, 0.0D)));

        ReadTsKvQueryResult max = aggregate(dao, scope, "np", Aggregation.MAX);
        assertNumericBuckets(
            max,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.DOUBLE, DataType.DOUBLE},
            new double[] {-3.0D, 0.0D},
            2400L);

        // MIN is not affected by the upstream bug; it must be byte-for-byte unchanged.
        ReadTsKvQueryResult min = aggregate(dao, scope, "np", Aggregation.MIN);
        assertNumericBuckets(
            min,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.DOUBLE, DataType.DOUBLE},
            new double[] {-5.0D, 0.0D},
            2400L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void maxOverNonPositiveLongOnlyAndMixedBucketsKeepsResultTypeAgainstRealIoTDB() throws Exception {
    TestScope scope =
        scope(
            "agg_nonpositive_types",
            "55555555-5555-5555-5555-555555555512",
            "66666666-6666-6666-6666-666666666612");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // Same regression across the two typed channels, since they take different code paths:
        //   Bucket [1000,2000): LONG-ONLY -5, -3 -> midpoint 1500, MAX = -3 kept LONG-typed via
        //       the direct MAX(long_v) channel (LongBigArray seeds with the true Long.MIN_VALUE,
        //       so that channel was always correct).
        //   Bucket [2000,3000): MIXED long -5 + double -3.0 -> midpoint 2500, MAX = -3.0 promoted
        //       to DOUBLE, which reads the numeric channel and therefore exercises the fix.
        saveAll(
            dao,
            scope,
            List.of(
                entry(1000L, "nt", DataType.LONG, -5L),
                entry(1500L, "nt", DataType.LONG, -3L),
                entry(2100L, "nt", DataType.LONG, -5L),
                entry(2400L, "nt", DataType.DOUBLE, -3.0D)));

        ReadTsKvQueryResult max = aggregate(dao, scope, "nt", Aggregation.MAX);
        assertNumericBuckets(
            max,
            new long[] {1500L, 2500L},
            new DataType[] {DataType.LONG, DataType.DOUBLE},
            new double[] {-3.0D, -3.0D},
            2400L);
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  @Test
  void calendarMonthBucketsFollowTheQueryTimezoneRatherThanUtc() throws Exception {
    TestScope scope =
        scope(
            "agg_month_tz",
            "55555555-5555-5555-5555-555555555509",
            "66666666-6666-6666-6666-666666666609");
    bootstrapSchema(scope.database());
    try (ITableSessionPool pool = newPool(scope.database())) {
      IoTDBTableConfig config = config(8);
      IoTDBTableTimeseriesWriter writer = new IoTDBTableTimeseriesWriter(pool, config);
      IoTDBTableTimeseriesDao dao = new IoTDBTableTimeseriesDao(pool, writer, config);
      try {
        // Asia/Shanghai is UTC+8 with no DST, so every calendar MONTH boundary sits exactly eight
        // hours EARLIER in epoch terms than the corresponding UTC boundary asserted above:
        //   Jan [1672502400000,1675180800000) 31d -> midpoint 1673841600000
        //   Feb [1675180800000,1677600000000) 28d -> midpoint 1676390400000
        //   Mar [1677600000000,1680278400000) 31d -> midpoint 1678939200000
        // Each midpoint is 28800000 ms below the UTC midpoint used by the test above.
        //
        // The middle sample is the discriminator. 1675195200000 is 2023-01-31T20:00Z, which UTC
        // bucketing places in JANUARY but Shanghai bucketing places in FEBRUARY (local time
        // 2023-02-01T04:00+08:00). If the DAO dropped the query timezone and fell back to UTC this
        // would collapse to TWO buckets carrying 60 and 7, not three carrying 10, 50 and 7 -- so
        // the test fails on the value distribution, not merely on the bucket labels.
        saveAll(
            dao,
            scope,
            List.of(
                entry(1673308800000L, "n", DataType.LONG, 10L), // 2023-01-10, Jan in both zones
                entry(1675195200000L, "n", DataType.LONG, 50L), // Jan in UTC, Feb in Shanghai
                entry(1677974400000L, "n", DataType.LONG, 7L))); // 2023-03-05, Mar in both zones

        long startTs = 1672502400000L; // 2023-01-01T00:00+08:00
        long endTs = 1680278400000L; // 2023-04-01T00:00+08:00
        long[] shanghaiMidpoints = {1673841600000L, 1676390400000L, 1678939200000L};

        ReadTsKvQueryResult sum =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.SUM, "Asia/Shanghai");
        assertNumericBuckets(
            sum,
            shanghaiMidpoints,
            new DataType[] {DataType.LONG, DataType.LONG, DataType.LONG},
            new double[] {10.0D, 50.0D, 7.0D},
            1677974400000L);

        ReadTsKvQueryResult count =
            calendarAggregate(dao, scope, "n", startTs, endTs, Aggregation.COUNT, "Asia/Shanghai");
        assertLongBuckets(count, shanghaiMidpoints, new long[] {1L, 1L, 1L});
      } finally {
        dao.destroy();
        writer.destroy();
      }
    }
  }

  private ReadTsKvQueryResult calendarAggregate(
      IoTDBTableTimeseriesDao dao,
      TestScope scope,
      String key,
      long startTs,
      long endTs,
      Aggregation aggregation)
      throws Exception {
    return calendarAggregate(dao, scope, key, startTs, endTs, aggregation, "UTC");
  }

  private ReadTsKvQueryResult calendarAggregate(
      IoTDBTableTimeseriesDao dao,
      TestScope scope,
      String key,
      long startTs,
      long endTs,
      Aggregation aggregation,
      String tzId)
      throws Exception {
    ReadTsKvQuery query =
        new BaseReadTsKvQuery(
            key,
            startTs,
            endTs,
            AggregationParams.calendar(aggregation, IntervalType.MONTH, tzId),
            100,
            "ASC");
    return dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
        .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .get(0);
  }

  private ReadTsKvQueryResult aggregate(
      IoTDBTableTimeseriesDao dao, TestScope scope, String key, Aggregation aggregation)
      throws Exception {
    ReadTsKvQuery query =
        new BaseReadTsKvQuery(key, 1000L, 3000L, INTERVAL, 100, aggregation, "ASC");
    return dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
        .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .get(0);
  }

  private ReadTsKvQueryResult precisionAggregate(
      IoTDBTableTimeseriesDao dao, TestScope scope, String key, Aggregation aggregation)
      throws Exception {
    ReadTsKvQuery query =
        new BaseReadTsKvQuery(key, 1000L, 5000L, INTERVAL, 100, aggregation, "ASC");
    return dao.findAllAsync(scope.tenantId(), scope.entityId(), List.of(query))
        .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .get(0);
  }

  private void assertDoubleBuckets(
      ReadTsKvQueryResult result,
      long[] expectedTs,
      double[] expectedValues,
      long expectedLastEntryTs) {
    List<TsKvEntry> data = result.getData();
    assertEquals(expectedTs.length, data.size(), "bucket count");
    for (int i = 0; i < expectedTs.length; i++) {
      TsKvEntry entry = data.get(i);
      assertEquals(expectedTs[i], entry.getTs(), "bucket ts at index " + i);
      assertEquals(DataType.DOUBLE, entry.getDataType(), "data type at index " + i);
      assertTrue(entry.getDoubleValue().isPresent(), "double value present at index " + i);
      assertEquals(
          expectedValues[i], entry.getDoubleValue().get(), 1e-9, "double value at index " + i);
    }
    // ThingsBoard reports lastEntryTs as MAX(underlying data ts), not a bucket midpoint.
    assertEquals(expectedLastEntryTs, result.getLastEntryTs(), "lastEntryTs");
  }

  private void assertLongBuckets(
      ReadTsKvQueryResult result, long[] expectedTs, long[] expectedValues) {
    List<TsKvEntry> data = result.getData();
    assertEquals(expectedTs.length, data.size(), "bucket count");
    for (int i = 0; i < expectedTs.length; i++) {
      TsKvEntry entry = data.get(i);
      assertEquals(expectedTs[i], entry.getTs(), "bucket ts at index " + i);
      assertEquals(DataType.LONG, entry.getDataType(), "data type at index " + i);
      assertEquals(
          Optional.of(expectedValues[i]), entry.getLongValue(), "long value at index " + i);
    }
  }

  private void assertStringBuckets(
      ReadTsKvQueryResult result, long[] expectedTs, String[] expectedValues) {
    List<TsKvEntry> data = result.getData();
    assertEquals(expectedTs.length, data.size(), "bucket count");
    for (int i = 0; i < expectedTs.length; i++) {
      TsKvEntry entry = data.get(i);
      assertEquals(expectedTs[i], entry.getTs(), "bucket ts at index " + i);
      assertEquals(DataType.STRING, entry.getDataType(), "data type at index " + i);
      assertEquals(
          Optional.of(expectedValues[i]), entry.getStrValue(), "string value at index " + i);
    }
  }

  /**
   * Asserts numeric buckets whose per-bucket result TYPE varies: a long-only SUM/MIN/MAX bucket
   * comes back {@link DataType#LONG}, a bucket with any participating double comes back {@link
   * DataType#DOUBLE}. {@code expectedTypes[i]} must be LONG or DOUBLE; the value is compared via
   * the matching typed getter (exact for LONG, 1e-9 tolerance for DOUBLE).
   */
  private void assertNumericBuckets(
      ReadTsKvQueryResult result,
      long[] expectedTs,
      DataType[] expectedTypes,
      double[] expectedValues,
      long expectedLastEntryTs) {
    List<TsKvEntry> data = result.getData();
    assertEquals(expectedTs.length, data.size(), "bucket count");
    for (int i = 0; i < expectedTs.length; i++) {
      TsKvEntry entry = data.get(i);
      assertEquals(expectedTs[i], entry.getTs(), "bucket ts at index " + i);
      assertEquals(expectedTypes[i], entry.getDataType(), "data type at index " + i);
      if (expectedTypes[i] == DataType.LONG) {
        assertEquals(
            Optional.of((long) expectedValues[i]),
            entry.getLongValue(),
            "long value at index " + i);
      } else {
        assertTrue(entry.getDoubleValue().isPresent(), "double value present at index " + i);
        assertEquals(
            expectedValues[i], entry.getDoubleValue().get(), 1e-9, "double value at index " + i);
      }
    }
    assertEquals(expectedLastEntryTs, result.getLastEntryTs(), "lastEntryTs");
  }

  /**
   * Like {@link #assertNumericBuckets} but keeps the expected LONG values in a {@code long[]} so a
   * value > 2^53 cannot be silently rounded by the test itself (the {@code double[]}-based helper
   * would lose the low bit of {@code 9007199254740993L}). For a LONG bucket the value is compared
   * EXACTLY via {@code getLongValue()}; for a DOUBLE bucket {@code expectedDoubleValues[i]} is
   * compared with a 1e-9 tolerance. This is the assertion that proves the precision contract: under
   * a COALESCE->DOUBLE round-trip (MIN/MAX) or a DOUBLE SUM accumulator the long results would come
   * back as {@code ...992} and fail here.
   */
  private void assertExactNumericBuckets(
      ReadTsKvQueryResult result,
      long[] expectedTs,
      DataType[] expectedTypes,
      long[] expectedLongValues,
      double[] expectedDoubleValues,
      long expectedLastEntryTs) {
    List<TsKvEntry> data = result.getData();
    assertEquals(expectedTs.length, data.size(), "bucket count");
    for (int i = 0; i < expectedTs.length; i++) {
      TsKvEntry entry = data.get(i);
      assertEquals(expectedTs[i], entry.getTs(), "bucket ts at index " + i);
      assertEquals(expectedTypes[i], entry.getDataType(), "data type at index " + i);
      if (expectedTypes[i] == DataType.LONG) {
        assertTrue(entry.getLongValue().isPresent(), "long value present at index " + i);
        assertEquals(
            expectedLongValues[i],
            entry.getLongValue().get().longValue(),
            "exact long value at index " + i);
      } else {
        assertTrue(entry.getDoubleValue().isPresent(), "double value present at index " + i);
        assertEquals(
            expectedDoubleValues[i],
            entry.getDoubleValue().get(),
            1e-9,
            "double value at index " + i);
      }
    }
    assertEquals(expectedLastEntryTs, result.getLastEntryTs(), "lastEntryTs");
  }

  private ITableSessionPool newPool(String database) {
    TableSessionPoolBuilder builder =
        new TableSessionPoolBuilder()
            .nodeUrls(List.of("127.0.0.1:" + IOTDB.getMappedPort(6667)))
            .user("root")
            .password("root")
            .maxSize(4);
    if (database != null) {
      builder.database(database);
    }
    return builder.build();
  }

  private void bootstrapSchema(String database) throws Exception {
    awaitIoTDBReady(database);

    String schema;
    try (InputStream stream =
        IoTDBTableTimeseriesAggregationIT.class
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
    try (ITableSessionPool bootstrapPool = newPool(null)) {
      try (ITableSession session = bootstrapPool.getSession()) {
        for (String statement : schema.split(";")) {
          String trimmed = statement.trim();
          if (!trimmed.isEmpty()) {
            session.executeNonQueryStatement(trimmed);
          }
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

  private IoTDBTableConfig config(int batchSize) {
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.getTs().getSave().setBatchSize(batchSize);
    config.getTs().getSave().setMaxLingerMs(20L);
    config.getTs().getSave().setRetryInitialBackoffMs(1L);
    config.getTs().getSave().setRetryMaxBackoffMs(1L);
    config.getTs().getRead().setThreads(1);
    return config;
  }

  private void saveAll(IoTDBTableTimeseriesDao dao, TestScope scope, List<TestTsKvEntry> entries)
      throws Exception {
    List<ListenableFuture<Integer>> futures = new ArrayList<>();
    for (TestTsKvEntry entry : entries) {
      futures.add(dao.save(scope.tenantId(), scope.entityId(), entry, 0));
    }
    for (ListenableFuture<Integer> future : futures) {
      assertEquals(1, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
  }

  private TestScope scope(String databasePrefix, String tenantId, String entityId) {
    return new TestScope(
        uniqueDatabase(databasePrefix),
        new TenantId(UUID.fromString(tenantId)),
        new TestEntityId(UUID.fromString(entityId), EntityType.DEVICE));
  }

  private String uniqueDatabase(String prefix) {
    String shortPrefix = prefix.length() > 12 ? prefix.substring(0, 12) : prefix;
    String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "tb_it_" + shortPrefix + "_" + shortUuid;
  }

  private TestTsKvEntry entry(long ts, String key, DataType dataType, Object value) {
    return new TestTsKvEntry(ts, key, dataType, value);
  }

  private record TestScope(String database, TenantId tenantId, EntityId entityId) {}

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

  private record TestTsKvEntry(long ts, String key, DataType dataType, Object value)
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
      return 1;
    }
  }
}
