/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.relational.flink.table;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.api.internal.TableImpl;
import org.apache.flink.table.planner.delegation.PlannerBase;
import org.apache.flink.table.planner.plan.schema.TableSourceTable;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Planner-driven test for the source pushdown. The Flink SQL is optimized through the real planner
 * (so the filter/projection/limit pushdown rules run and mutate the source), but the resulting
 * IoTDB query is only rendered, never executed.
 */
public class IoTDBRelationalSourcePushDownPlannerTest {

  private static final String DDL =
      "CREATE TABLE iotdb_t (\n"
          + "  `time` TIMESTAMP(3),\n"
          + "  `device_id` STRING,\n"
          + "  `temperature` DOUBLE,\n"
          + "  `humidity` DOUBLE\n"
          + ") WITH (\n"
          + "  'connector' = 'iotdb-relational',\n"
          + "  'iotdb.node-urls' = '127.0.0.1:6667',\n"
          + "  'iotdb.user' = 'root',\n"
          + "  'iotdb.password' = 'root',\n"
          + "  'iotdb.database' = 'test',\n"
          + "  'iotdb.table' = 'sensor',\n"
          + "  'iotdb.time-column' = 'time',\n"
          + "  'iotdb.tag-columns' = 'device_id'\n"
          + ")";

  @Test
  public void testProjectionFilterAndLimitPushDown() {
    // Filter on device_id and select columns that are not made constant by the filter.
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT `time`, temperature FROM iotdb_t WHERE device_id = 'd1' LIMIT 5");

    assertEquals(
        Collections.singletonList("(\"device_id\" = 'd1')"), source.getResolvedFilterQueries());
    assertEquals(5L, source.getLimit());
    assertEquals(
        "SELECT \"time\", \"temperature\" FROM \"sensor\" "
            + "WHERE (\"device_id\" = 'd1') LIMIT 5",
        source.buildQuery());
  }

  @Test
  public void testProjectionOnly() {
    IoTDBRelationalDynamicTableSource source = optimize("SELECT device_id FROM iotdb_t");

    assertEquals(Collections.emptyList(), source.getResolvedFilterQueries());
    assertEquals(-1L, source.getLimit());
    assertEquals("SELECT \"device_id\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testIsNullPushDown() {
    // Under `device_id IS NULL`, Flink constant-folds device_id, so select another column.
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE device_id IS NULL");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" WHERE (\"device_id\" IS NULL)",
        source.buildQuery());
  }

  @Test
  public void testInPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE device_id IN ('d1', 'd2')");

    // Flink normalizes IN into a chain of OR comparisons before pushdown.
    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" "
            + "WHERE ((\"device_id\" = 'd1') OR (\"device_id\" = 'd2'))",
        source.buildQuery());
  }

  @Test
  public void testLikePushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE device_id LIKE 'd%'");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (\"device_id\" LIKE 'd%')",
        source.buildQuery());
  }

  @Test
  public void testArithmeticFilterPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE temperature + humidity > 30.0E0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" " + "WHERE ((\"temperature\" + \"humidity\") > 30.0)",
        source.buildQuery());
  }

  @Test
  public void testMultiplicationFilterPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE temperature * 2.0E0 > 40.0E0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE ((\"temperature\" * 2.0) > 40.0)",
        source.buildQuery());
  }

  @Test
  public void testOrPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE device_id = 'd1' OR device_id = 'd2'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" "
            + "WHERE ((\"device_id\" = 'd1') OR (\"device_id\" = 'd2'))",
        source.buildQuery());
  }

  @Test
  public void testIsNotNullPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE temperature IS NOT NULL");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (\"temperature\" IS NOT NULL)",
        source.buildQuery());
  }

  @Test
  public void testFunctionPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE LOWER(device_id) = 'd1'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" WHERE (lower(\"device_id\") = 'd1')",
        source.buildQuery());
  }

  @Test
  public void testNotEqualsPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE temperature <> 0.0E0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (\"temperature\" <> 0.0)", source.buildQuery());
  }

  @Test
  public void testLessOrEqualPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE temperature <= 30.0E0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (\"temperature\" <= 30.0)",
        source.buildQuery());
  }

  @Test
  public void testGreaterOrEqualPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE temperature >= 30.0E0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (\"temperature\" >= 30.0)",
        source.buildQuery());
  }

  @Test
  public void testCastPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE CAST(temperature AS INT) > 0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (CAST(\"temperature\" AS INT32) > 0)",
        source.buildQuery());
  }

  @Test
  public void testCoalescePushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE COALESCE(temperature, 0.0E0) > 10.0E0");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (coalesce(\"temperature\", 0.0) > 10.0)",
        source.buildQuery());
  }

  @Test
  public void testUpperFunctionPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE UPPER(device_id) = 'D1'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" WHERE (upper(\"device_id\") = 'D1')",
        source.buildQuery());
  }

  @Test
  public void testPartialPushDown() {
    // device_id = 'd1' is mappable, RAND() is not; select a non-constrained column so it is not
    // constant-folded.
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE device_id = 'd1' AND RAND() > 0.5");

    assertEquals(
        Collections.singletonList("(\"device_id\" = 'd1')"), source.getResolvedFilterQueries());
    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" WHERE (\"device_id\" = 'd1')", source.buildQuery());
  }

  @Test
  public void testLtrimPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE LTRIM(device_id) = 'd1'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" "
            + "WHERE (trim(LEADING FROM \"device_id\") = 'd1')",
        source.buildQuery());
  }

  @Test
  public void testRtrimPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE RTRIM(device_id) = 'd1'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" "
            + "WHERE (trim(TRAILING FROM \"device_id\") = 'd1')",
        source.buildQuery());
  }

  @Test
  public void testMd5PushDown() {
    // Flink wraps the hash result in a CAST to STRING before the comparison.
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE MD5(device_id) = 'x'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" "
            + "WHERE (CAST(md5(\"device_id\") AS STRING) = 'x')",
        source.buildQuery());
  }

  @Test
  public void testSha256PushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT temperature FROM iotdb_t WHERE SHA256(device_id) = 'x'");

    assertEquals(
        "SELECT \"temperature\" FROM \"sensor\" "
            + "WHERE (CAST(sha256(\"device_id\") AS STRING) = 'x')",
        source.buildQuery());
  }

  @Test
  public void testNowPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE `time` < NOW()");

    assertEquals(
        "SELECT \"device_id\" FROM \"sensor\" WHERE (\"time\" < now())", source.buildQuery());
  }

  @Test
  public void testUnsupportedPredicateIsNotPushed() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id FROM iotdb_t WHERE device_id = uuid()");

    assertEquals(Collections.emptyList(), source.getResolvedFilterQueries());
    assertEquals("SELECT \"device_id\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testGlobalCountStarIsNotPushedDown() {
    // Flink 1.17 feeds a global COUNT(*) through a constant Calc, and the pushdown rule only
    // accepts field-projection Calcs, so it is intentionally left to Flink.
    IoTDBRelationalDynamicTableSource source = optimize("SELECT COUNT(*) FROM iotdb_t");

    assertEquals("SELECT \"time\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testCountColumnPushDown() {
    IoTDBRelationalDynamicTableSource source = optimize("SELECT COUNT(temperature) FROM iotdb_t");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT CAST(COUNT(\"temperature\") AS INT64) FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testSumPushDown() {
    IoTDBRelationalDynamicTableSource source = optimize("SELECT SUM(temperature) FROM iotdb_t");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT CAST(SUM(\"temperature\") AS DOUBLE) FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testAvgDecomposedPushDown() {
    // AVG is decomposed by the planner into SUM0 + COUNT and both must be pushed down.
    IoTDBRelationalDynamicTableSource source = optimize("SELECT AVG(temperature) FROM iotdb_t");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT CAST(SUM(\"temperature\") AS DOUBLE), CAST(COUNT(\"temperature\") AS INT64) "
            + "FROM \"sensor\"",
        source.buildQuery());
  }

  @Test
  public void testMaxAndMinPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT MAX(temperature), MIN(temperature) FROM iotdb_t");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT CAST(MAX(\"temperature\") AS DOUBLE), CAST(MIN(\"temperature\") AS DOUBLE) "
            + "FROM \"sensor\"",
        source.buildQuery());
  }

  @Test
  public void testMinStringPushDown() {
    IoTDBRelationalDynamicTableSource source = optimize("SELECT MIN(device_id) FROM iotdb_t");

    assertNotNull(source.getAggregateSpec());
    assertEquals("SELECT CAST(MIN(\"device_id\") AS STRING) FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testGroupByCountPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id, COUNT(*) FROM iotdb_t GROUP BY device_id");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT \"device_id\", CAST(COUNT(*) AS INT64) FROM \"sensor\" GROUP BY \"device_id\"",
        source.buildQuery());
  }

  @Test
  public void testGroupBySumPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id, SUM(temperature) FROM iotdb_t GROUP BY device_id");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT \"device_id\", CAST(SUM(\"temperature\") AS DOUBLE) FROM \"sensor\" "
            + "GROUP BY \"device_id\"",
        source.buildQuery());
  }

  @Test
  public void testAggregateWithFilterPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT SUM(temperature) FROM iotdb_t WHERE temperature > 30.0E0");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        Collections.singletonList("(\"temperature\" > 30.0)"), source.getResolvedFilterQueries());
    assertEquals(
        "SELECT CAST(SUM(\"temperature\") AS DOUBLE) FROM \"sensor\" "
            + "WHERE (\"temperature\" > 30.0)",
        source.buildQuery());
  }

  @Test
  public void testMultipleAggregatesPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize(
            "SELECT device_id, COUNT(*), SUM(temperature), MAX(humidity), MIN(temperature) "
                + "FROM iotdb_t GROUP BY device_id");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT \"device_id\", CAST(COUNT(*) AS INT64), CAST(SUM(\"temperature\") AS DOUBLE), "
            + "CAST(MAX(\"humidity\") AS DOUBLE), CAST(MIN(\"temperature\") AS DOUBLE) "
            + "FROM \"sensor\" GROUP BY \"device_id\"",
        source.buildQuery());
  }

  @Test
  public void testGroupByMultipleColumnsPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize(
            "SELECT device_id, temperature, COUNT(*) FROM iotdb_t "
                + "GROUP BY device_id, temperature");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT \"device_id\", \"temperature\", CAST(COUNT(*) AS INT64) FROM \"sensor\" "
            + "GROUP BY \"device_id\", \"temperature\"",
        source.buildQuery());
  }

  @Test
  public void testAggregateWithFilterAndGroupByPushDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize(
            "SELECT device_id, AVG(temperature) FROM iotdb_t "
                + "WHERE humidity > 10.0E0 GROUP BY device_id");

    assertNotNull(source.getAggregateSpec());
    assertEquals(
        "SELECT \"device_id\", CAST(SUM(\"temperature\") AS DOUBLE), "
            + "CAST(COUNT(\"temperature\") AS INT64) FROM \"sensor\" "
            + "WHERE (\"humidity\" > 10.0) GROUP BY \"device_id\"",
        source.buildQuery());
  }

  @Test
  public void testSumOfExpressionIsNotPushedDown() {
    // sum(a + b): the argument is an expression, which Flink evaluates in a Calc before the
    // local aggregate, so the pushdown rule leaves it to Flink.
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT SUM(temperature + humidity) FROM iotdb_t");

    assertEquals("SELECT \"temperature\", \"humidity\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testSumOfScalingExpressionIsNotPushedDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT SUM(temperature * 2.0E0) FROM iotdb_t");

    assertEquals("SELECT \"temperature\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testAggregateOfFunctionIsNotPushedDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT MAX(LOWER(device_id)) FROM iotdb_t");

    assertEquals("SELECT \"device_id\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testGroupByExpressionIsNotPushedDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT device_id || 'x', COUNT(*) FROM iotdb_t GROUP BY device_id || 'x'");

    assertEquals("SELECT \"device_id\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testCountDistinctIsNotPushedDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT COUNT(DISTINCT device_id) FROM iotdb_t");

    assertEquals("SELECT \"device_id\" FROM \"sensor\"", source.buildQuery());
  }

  @Test
  public void testUnsupportedAggregateIsNotPushedDown() {
    IoTDBRelationalDynamicTableSource source =
        optimize("SELECT STDDEV_POP(temperature) FROM iotdb_t");

    assertEquals("SELECT \"temperature\" FROM \"sensor\"", source.buildQuery());
  }

  private static IoTDBRelationalDynamicTableSource optimize(String query) {
    TableEnvironmentImpl tableEnvironment =
        (TableEnvironmentImpl) TableEnvironment.create(EnvironmentSettings.inBatchMode());
    // Aggregate pushdown is opt-in and needs a local (partial) aggregate to be generated.
    tableEnvironment.getConfig().set("table.optimizer.source.aggregate-pushdown-enabled", "true");
    tableEnvironment.getConfig().set("table.optimizer.agg-phase-strategy", "TWO_PHASE");
    tableEnvironment.executeSql(DDL);
    Table table = tableEnvironment.sqlQuery(query);

    PlannerBase planner = (PlannerBase) tableEnvironment.getPlanner();
    RelNode logical =
        planner.createRelBuilder().queryOperation(((TableImpl) table).getQueryOperation()).build();
    RelNode optimized = planner.optimize(logical);

    IoTDBRelationalDynamicTableSource source = findSource(optimized);
    assertNotNull(source);
    return source;
  }

  private static IoTDBRelationalDynamicTableSource findSource(RelNode rel) {
    RelOptTable table = rel.getTable();
    if (table != null) {
      TableSourceTable sourceTable = table.unwrap(TableSourceTable.class);
      if (sourceTable != null
          && sourceTable.tableSource() instanceof IoTDBRelationalDynamicTableSource) {
        return (IoTDBRelationalDynamicTableSource) sourceTable.tableSource();
      }
    }
    for (RelNode input : rel.getInputs()) {
      IoTDBRelationalDynamicTableSource source = findSource(input);
      if (source != null) {
        return source;
      }
    }
    return null;
  }
}
