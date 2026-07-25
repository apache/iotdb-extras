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
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the table-level TTL mechanism against real IoTDB 2.0.8. IoTDB Table Mode TTL is a table
 * property expressed in milliseconds; this IT proves the two operator-facing paths the README
 * documents work as described:
 *
 * <ul>
 *   <li>the schema's {@code WITH (TTL=DEFAULT)} bootstraps and resolves to the database default
 *       ({@code INF}, i.e. never expire), and
 *   <li>{@code ALTER TABLE telemetry SET PROPERTIES TTL=<ms>} sets a concrete millisecond retention
 *       at runtime, and {@code CREATE TABLE ... WITH (TTL=<ms>)} sets it at create time.
 * </ul>
 *
 * <p>The asserted TTL is read back from {@code information_schema.tables} (the {@code ttl(ms)}
 * column). This validates the TTL DDL/property mechanism only. It deliberately does NOT assert
 * physical row eviction: IoTDB TTL eviction is async and compaction-driven, so observed row drops
 * are not deterministic within a test and are out of scope here.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class IoTDBTableTtlIT {
  private static final Duration IOTDB_STARTUP_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IOTDB_READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration IOTDB_READY_POLL_INTERVAL = Duration.ofMillis(500);

  // 7 days expressed in milliseconds: IoTDB Table Mode TTL is a bare long literal in ms.
  private static final long SEVEN_DAYS_MS = TimeUnit.DAYS.toMillis(7);
  // 1 day in ms, used for the CREATE-time path.
  private static final long ONE_DAY_MS = TimeUnit.DAYS.toMillis(1);

  @Container
  static final GenericContainer<?> IOTDB =
      new GenericContainer<>(DockerImageName.parse("apache/iotdb:2.0.8-standalone"))
          .withExposedPorts(6667)
          // IoTDB binds its client RPC service to dn_rpc_address (default 127.0.0.1); bind to all
          // interfaces so the Testcontainers port-mapped session handshake succeeds.
          .withEnv("dn_rpc_address", "0.0.0.0")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(IOTDB_STARTUP_TIMEOUT));

  @Test
  void schemaBootstrapsWithDefaultTtlThenAlterSetsConcreteMillisecondRetention() throws Exception {
    String database = uniqueDatabase("ttl_alter");
    bootstrapSchema(database);
    try (ITableSessionPool pool = newPool(database)) {
      // The shipped schema declares telemetry WITH (TTL=DEFAULT); DEFAULT resolves to the database
      // default retention, which on a fresh node is INF (never expire).
      assertEquals("INF", readTableTtlMs(pool, database, "telemetry"));

      // The same schema declares entity_attributes WITH (TTL='INF'). The QUOTED string is the only
      // accepted never-expire literal: an unquoted INF is parsed as an identifier and rejected with
      // "ttl value must be a LongLiteral, but now is Identifier". Asserting the read-back pins the
      // exact form the shipped schema depends on, not just that the bootstrap did not throw.
      assertEquals("INF", readTableTtlMs(pool, database, "entity_attributes"));

      // Operator switches to a concrete retention at runtime via ALTER ... SET PROPERTIES TTL=<ms>.
      try (ITableSession session = pool.getSession()) {
        session.executeNonQueryStatement(
            "ALTER TABLE telemetry SET PROPERTIES TTL=" + SEVEN_DAYS_MS);
      }
      assertEquals(Long.toString(SEVEN_DAYS_MS), readTableTtlMs(pool, database, "telemetry"));

      // And can revert to the database default (INF here) the same way.
      try (ITableSession session = pool.getSession()) {
        session.executeNonQueryStatement("ALTER TABLE telemetry SET PROPERTIES TTL=DEFAULT");
      }
      assertEquals("INF", readTableTtlMs(pool, database, "telemetry"));
    }
  }

  @Test
  void createTableWithConcreteMillisecondTtlIsReadBackExactly() throws Exception {
    String database = uniqueDatabase("ttl_create");
    bootstrapSchema(database);
    try (ITableSessionPool pool = newPool(database)) {
      // The schema can equally ship a concrete retention at create time: WITH (TTL=<ms>).
      try (ITableSession session = pool.getSession()) {
        session.executeNonQueryStatement(
            "CREATE TABLE telemetry_ttl ("
                + "tenant_id STRING TAG, entity_type STRING TAG, entity_id STRING TAG, "
                + "key STRING TAG, long_v INT64 FIELD) WITH (TTL="
                + ONE_DAY_MS
                + ")");
      }
      assertEquals(Long.toString(ONE_DAY_MS), readTableTtlMs(pool, database, "telemetry_ttl"));
    }
  }

  /**
   * Reads the table's TTL back from {@code information_schema.tables}. The {@code ttl(ms)} column
   * is the millisecond retention, or the literal {@code INF} when the table never expires.
   */
  private String readTableTtlMs(ITableSessionPool pool, String database, String table)
      throws Exception {
    String sql =
        "SELECT \"ttl(ms)\" FROM information_schema.tables "
            + "WHERE database='"
            + database
            + "' AND table_name='"
            + table
            + "'";
    try (ITableSession session = pool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      assertTrue(row.next(), "expected one information_schema row for table " + table);
      return row.getString("ttl(ms)");
    }
  }

  private ITableSessionPool newPool(String database) {
    TableSessionPoolBuilder builder =
        new TableSessionPoolBuilder()
            .nodeUrls(List.of("127.0.0.1:" + IOTDB.getMappedPort(6667)))
            .user("root")
            .password("root")
            .maxSize(2);
    if (database != null) {
      builder.database(database);
    }
    return builder.build();
  }

  private void bootstrapSchema(String database) throws Exception {
    awaitIoTDBReady(database);

    String schema;
    try (InputStream stream =
        IoTDBTableTtlIT.class.getClassLoader().getResourceAsStream("schema-iotdb-table.sql")) {
      schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    schema =
        schema
            .replace(
                "CREATE DATABASE IF NOT EXISTS thingsboard;",
                "CREATE DATABASE IF NOT EXISTS " + database + ";")
            .replace("USE thingsboard;", "USE " + database + ";");
    schema = schema.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "");
    try (ITableSessionPool bootstrapPool = newPool(null);
        ITableSession session = bootstrapPool.getSession()) {
      for (String statement : schema.split(";")) {
        String trimmed = statement.trim();
        if (!trimmed.isEmpty()) {
          session.executeNonQueryStatement(trimmed);
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

  private String uniqueDatabase(String prefix) {
    String shortPrefix = prefix.length() > 12 ? prefix.substring(0, 12) : prefix;
    String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "tb_it_" + shortPrefix + "_" + shortUuid;
  }
}
