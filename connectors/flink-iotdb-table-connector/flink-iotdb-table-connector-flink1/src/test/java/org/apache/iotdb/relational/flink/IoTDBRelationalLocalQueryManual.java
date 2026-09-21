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

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

/**
 * Temporary manual verification class. This is intentionally kept in test sources and should not be
 * committed as a production test.
 *
 * <p>The flow is write first and then query: rows are inserted through the connector and read back
 * with a SELECT, followed by a lookup join (both sync and async) executed as Flink SQL.
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
            .append("  'nodeUrls' = '")
            .append(nodeUrls)
            .append("',\n")
            .append("  'user' = '")
            .append(user)
            .append("',\n")
            .append("  'password' = '")
            .append(password)
            .append("',\n")
            .append("  'database' = '")
            .append(database)
            .append("',\n")
            .append("  'table' = '")
            .append(table)
            .append("',\n")
            .append("  'time-column' = 'time',\n")
            .append("  'tag-columns' = 'device_id'");
    if (async != null) {
      ddl.append(",\n  'lookup.async' = '").append(async).append("'");
    }
    ddl.append("\n)");
    return ddl.toString();
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
