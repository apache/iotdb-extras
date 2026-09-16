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
 * <p>Run with system properties such as:
 *
 * <pre>
 * -Diotdb.nodeUrls=127.0.0.1:6667
 * -Diotdb.user=root
 * -Diotdb.password=root
 * -Diotdb.database=test
 * -Diotdb.table=sensor
 * </pre>
 */
public class IoTDBRelationalLocalQueryManual {

  public static void main(String[] args) throws Exception {
    String nodeUrls = System.getProperty("iotdb.nodeUrls", "127.0.0.1:6667");
    String user = System.getProperty("iotdb.user", "root");
    String password = System.getProperty("iotdb.password", "root");
    String database = System.getProperty("iotdb.database", "test");
    String table = System.getProperty("iotdb.table", "sensor");

    TableEnvironment tableEnvironment = TableEnvironment.create(EnvironmentSettings.inBatchMode());

    tableEnvironment.executeSql("DROP TABLE IF EXISTS iotdb_source");

    // Replace these columns with the actual columns and types in the local IoTDB table.
    String ddl =
        String.format(
            "CREATE TABLE iotdb_source (\n"
                + "  `time` TIMESTAMP(3),\n"
                + "  `device_id` STRING,\n"
                + "  `temperature` DOUBLE\n"
                + ") WITH (\n"
                + "  'connector' = 'iotdb-relational',\n"
                + "  'nodeUrls' = '%s',\n"
                + "  'user' = '%s',\n"
                + "  'password' = '%s',\n"
                + "  'database' = '%s',\n"
                + "  'table' = '%s'\n"
                + ")",
            nodeUrls, user, password, database, table);

    System.out.println("DDL:\n" + ddl);
    tableEnvironment.executeSql(ddl);

    run(tableEnvironment, "SELECT * FROM iotdb_source LIMIT 5");
    run(
        tableEnvironment,
        "SELECT device_id, temperature FROM iotdb_source WHERE temperature > 0 LIMIT 5");
    run(
        tableEnvironment,
        "SELECT device_id, temperature FROM iotdb_source " + "WHERE temperature + 1 > 0 LIMIT 5");
  }

  private static void run(TableEnvironment tableEnvironment, String sql) throws Exception {
    System.out.println("\n=== EXECUTE ===");
    System.out.println(sql);

    TableResult result = tableEnvironment.executeSql(sql);
    try (CloseableIterator<Row> iterator = result.collect()) {
      while (iterator.hasNext()) {
        System.out.println(iterator.next());
      }
    }
  }
}
