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

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

/**
 * Temporary manual verification class. This is intentionally kept in test sources and should not be
 * committed as a production test.
 *
 * <p>The flow is write first and then query: rows are inserted through the connector and read back
 * with a SELECT.
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
    String ddl =
        String.format(
            "CREATE TABLE iotdb_table (\n"
                + "  `time` TIMESTAMP(3),\n"
                + "  `device_id` STRING,\n"
                + "  `temperature` DOUBLE\n"
                + ") WITH (\n"
                + "  'connector' = 'iotdb-relational',\n"
                + "  'nodeUrls' = '%s',\n"
                + "  'user' = '%s',\n"
                + "  'password' = '%s',\n"
                + "  'database' = '%s',\n"
                + "  'table' = '%s',\n"
                + "  'time-column' = 'time',\n"
                + "  'tag-columns' = 'device_id'\n"
                + ")",
            nodeUrls, user, password, database, table);

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
    run(
        tableEnvironment,
        "SELECT temperature FROM iotdb_table WHERE temperature + 1 > 21 LIMIT 5");
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
