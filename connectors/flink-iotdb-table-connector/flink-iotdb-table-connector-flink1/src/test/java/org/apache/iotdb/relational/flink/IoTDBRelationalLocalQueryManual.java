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

package org.apache.iotdb.relational.flink;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.session.TableSessionBuilder;
import org.apache.iotdb.session.subscription.ISubscriptionTableSession;
import org.apache.iotdb.session.subscription.SubscriptionTableSessionBuilder;
import org.apache.iotdb.session.subscription.model.Subscription;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import java.util.Arrays;
import java.util.Set;

/**
 * Temporary manual verification class. This is intentionally kept in test sources and should not be
 * committed as a production test.
 *
 * <p>The flow is write first and then query: rows are inserted through the connector and read back
 * with a SELECT, followed by a lookup join (both sync and async) and a subscription-backed CDC
 * read, all executed as Flink SQL.
 *
 * <p>Run with system properties such as:
 *
 * <pre>
 * -Diotdb.nodeUrls=127.0.0.1:6667
 * -Diotdb.user=root
 * -Diotdb.password=root
 * -Diotdb.database=test
 * -Diotdb.table=sensor
 * </pre>
 *
 * <p>The IoTDB table {@code <database>.<table>} must already exist and its columns must match the
 * DDL below: {@code time} (TIME), {@code device_id} (TAG), {@code temperature} (FIELD).
 */
public class IoTDBRelationalLocalQueryManual {

  public static void main(String[] args) throws Exception {
    String nodeUrls = System.getProperty("iotdb.nodeUrls", "127.0.0.1:6667");
    String user = System.getProperty("iotdb.user", "root");
    String password = System.getProperty("iotdb.password", "root");
    String database = System.getProperty("iotdb.database", "test");
    String table = System.getProperty("iotdb.table", "sensor");

    TableEnvironment tableEnvironment = TableEnvironment.create(EnvironmentSettings.inBatchMode());

    tableEnvironment.executeSql("DROP TABLE IF EXISTS iotdb_table");

    // Replace these columns with the actual columns and categories in the local IoTDB table.
    String ddl = buildDdl("iotdb_table", nodeUrls, user, password, database, table, null);
    System.out.println("DDL:\n" + ddl);
    tableEnvironment.executeSql(ddl);

    // 1) Write: the connector turns the selected rows into IoTDB tablets.
    String insert =
        "INSERT INTO iotdb_table\n"
            + "SELECT ts, device_id, temperature FROM (\n"
            + "  VALUES\n"
            + "    (CAST(TIMESTAMP '2024-01-01 00:00:00' AS TIMESTAMP(3)), 'd1', 20.5),\n"
            + "    (CAST(TIMESTAMP '2024-01-01 00:00:01' AS TIMESTAMP(3)), 'd1', 21.0),\n"
            + "    (CAST(TIMESTAMP '2024-01-01 00:00:02' AS TIMESTAMP(3)), 'd2', 19.5)\n"
            + ") AS source_table(ts, device_id, temperature)";
    execute(tableEnvironment, insert);

    // 2) Read: the same connector table can be used as a source.
    run(tableEnvironment, "SELECT * FROM iotdb_table");
    run(
        tableEnvironment,
        "SELECT device_id, temperature FROM iotdb_table WHERE temperature > 0 LIMIT 1");
    run(tableEnvironment, "SELECT temperature FROM iotdb_table WHERE temperature + 1 > 21 LIMIT 5");

    // 3) Lookup: run a temporal join through SQL for both sync and async lookup.
    verifyLookupJoin(nodeUrls, user, password, database, table, false);
    verifyLookupJoin(nodeUrls, user, password, database, table, true);

    // 4) CDC: read the table through the subscription-backed CDC source.
    verifyCdc(nodeUrls, user, password, database, table);
  }

  /**
   * Flushes the database so the written rows are sealed into TsFiles and thus visible to the
   * subscription snapshot phase (mirrors the official subscription example, which flushes before
   * subscribing).
   */
  private static void flushDatabase(
      String nodeUrls, String user, String password, String database) throws Exception {
    System.out.println("\n=== FLUSH database '" + database + "' ===");
    try (ITableSession session =
        new TableSessionBuilder()
            .nodeUrls(Arrays.asList(nodeUrls.split(",")))
            .username(user)
            .password(password)
            .database(database)
            .build()) {
      session.executeNonQueryStatement("flush");
    }
    long waitMs = Long.getLong("iotdb.cdc.flush.wait.ms", 3000L);
    if (waitMs > 0) {
      System.out.println("Waiting " + waitMs + " ms for the flush to seal data...");
      Thread.sleep(waitMs);
    }
    System.out.println("Flush finished.");
  }

  /**
   * Drops any existing subscription and topic for this (database, table) so each run starts from a
   * clean subscription state. The names mirror the ones derived by {@code IoTDBOptions}.
   */
  private static void resetCdcSubscription(
      String nodeUrls, String user, String password, String database, String table) {
    String topic = "flink_iotdb_table_" + sanitize(database) + "_" + sanitize(table);
    String[] hostPort = nodeUrls.split(",")[0].split(":");
    System.out.println("\n=== CDC RESET: drop subscriptions and topic '" + topic + "' ===");

    try (ISubscriptionTableSession session =
        new SubscriptionTableSessionBuilder()
            .host(hostPort[0])
            .port(Integer.parseInt(hostPort[1]))
            .username(user)
            .password(password)
            .build()) {
      session.open();
      try {
        for (Subscription subscription : session.getSubscriptions(topic)) {
          System.out.println("  drop subscription " + subscription.getSubscriptionId());
          session.dropSubscriptionIfExists(subscription.getSubscriptionId());
        }
      } catch (Exception e) {
        System.out.println("  list/drop subscriptions failed: " + e.getMessage());
      }
      session.dropTopicIfExists(topic);
      System.out.println("  reset done.");
    } catch (Exception e) {
      System.out.println("  CDC reset skipped/failed: " + e.getMessage());
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
  }

  /**
   * Reads the table through the CDC source. The database is flushed first so the written rows are
   * visible to the subscription snapshot phase, then the topic/subscription is reset and recreated
   * in {@code initial} mode (full + incremental); a LIMIT bounds the otherwise unbounded stream.
   */
  private static void verifyCdc(
      String nodeUrls, String user, String password, String database, String table)
      throws Exception {
    flushDatabase(nodeUrls, user, password, database);
    resetCdcSubscription(nodeUrls, user, password, database, table);

    StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
    environment.setParallelism(1);
    StreamTableEnvironment tableEnvironment = StreamTableEnvironment.create(environment);

    String flinkTable = "iotdb_cdc";
    tableEnvironment.executeSql("DROP TABLE IF EXISTS " + flinkTable);
    String ddl = buildCdcDdl(flinkTable, nodeUrls, user, password, database, table);
    System.out.println("\n=== CDC DDL ===\n" + ddl);
    tableEnvironment.executeSql(ddl);

    System.out.println("\n=== CDC (snapshot mode) ===");
    String sql = "SELECT * FROM " + flinkTable + "";
    System.out.println(sql);
    TableResult result = tableEnvironment.executeSql(sql);
    try (CloseableIterator<Row> iterator = result.collect()) {
      while (iterator.hasNext()) {
        System.out.println(iterator.next());
      }
    }
    System.out.println("CDC read finished.");
  }

  private static void verifyLookupJoin(
      String nodeUrls, String user, String password, String database, String table, boolean async)
      throws Exception {
    StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
    environment.setParallelism(1);
    StreamTableEnvironment tableEnvironment = StreamTableEnvironment.create(environment);

    String flinkTable = "iotdb_lookup_" + (async ? "async" : "sync");
    tableEnvironment.executeSql("DROP TABLE IF EXISTS " + flinkTable);
    tableEnvironment.executeSql(
        buildDdl(flinkTable, nodeUrls, user, password, database, table, async));

    tableEnvironment.executeSql(
        "CREATE TEMPORARY VIEW probe AS "
            + "SELECT device_id, PROCTIME() AS proc_time FROM "
            + flinkTable);

    System.out.println("\n=== LOOKUP " + (async ? "ASYNC" : "SYNC") + " ===");
    String sql =
        "SELECT p.device_id, d.temperature FROM probe AS p "
            + "JOIN "
            + flinkTable
            + " FOR SYSTEM_TIME AS OF p.proc_time AS d "
            + "ON p.device_id = d.device_id";
    System.out.println(sql);

    TableResult result = tableEnvironment.executeSql(sql);
    try (CloseableIterator<Row> iterator = result.collect()) {
      while (iterator.hasNext()) {
        System.out.println(iterator.next());
      }
    }
  }

  private static String buildDdl(
      String flinkTable,
      String nodeUrls,
      String user,
      String password,
      String database,
      String table,
      Boolean async) {
    StringBuilder ddl =
        new StringBuilder()
            .append("CREATE TABLE ")
            .append(flinkTable)
            .append(" (\n")
            .append("  `time` TIMESTAMP(3),\n")
            .append("  `device_id` STRING,\n")
            .append("  `temperature` DOUBLE\n")
            .append(") WITH (\n")
            .append("  'connector' = 'iotdb-relational',\n")
            .append("  'iotdb.node-urls' = '")
            .append(nodeUrls)
            .append("',\n")
            .append("  'iotdb.user' = '")
            .append(user)
            .append("',\n")
            .append("  'iotdb.password' = '")
            .append(password)
            .append("',\n")
            .append("  'iotdb.database' = '")
            .append(database)
            .append("',\n")
            .append("  'iotdb.table' = '")
            .append(table)
            .append("',\n")
            .append("  'iotdb.time-column' = 'time',\n")
            .append("  'iotdb.tag-columns' = 'device_id'");
    if (async != null) {
      ddl.append(",\n  'iotdb.lookup.async' = '").append(async).append("'");
    }
    ddl.append("\n)");
    return ddl.toString();
  }

  private static String buildCdcDdl(
      String flinkTable,
      String nodeUrls,
      String user,
      String password,
      String database,
      String table) {
    return "CREATE TABLE "
        + flinkTable
        + " (\n"
        + "  `time` TIMESTAMP(3),\n"
        + "  `device_id` STRING,\n"
        + "  `temperature` DOUBLE\n"
        + ") WITH (\n"
        + "  'connector' = 'iotdb-relational',\n"
        + "  'iotdb.node-urls' = '"
        + nodeUrls
        + "',\n"
        + "  'iotdb.user' = '"
        + user
        + "',\n"
        + "  'iotdb.password' = '"
        + password
        + "',\n"
        + "  'iotdb.database' = '"
        + database
        + "',\n"
        + "  'iotdb.table' = '"
        + table
        + "',\n"
        + "  'iotdb.time-column' = 'time',\n"
        + "  'iotdb.tag-columns' = 'device_id',\n"
        + "  'iotdb.scan.mode' = 'cdc',\n"
        + "  'iotdb.cdc.mode' = 'initial'\n"
        + ")";
  }

  private static void execute(TableEnvironment tableEnvironment, String sql) throws Exception {
    System.out.println("\n=== WRITE ===");
    System.out.println(sql);
    tableEnvironment.executeSql(sql).await();
    System.out.println("Write finished.");
  }

  private static void run(TableEnvironment tableEnvironment, String sql) throws Exception {
    System.out.println("\n=== READ ===");
    System.out.println(sql);

    TableResult result = tableEnvironment.executeSql(sql);
    try (CloseableIterator<Row> iterator = result.collect()) {
      while (iterator.hasNext()) {
        System.out.println(iterator.next());
      }
    }
  }
}
