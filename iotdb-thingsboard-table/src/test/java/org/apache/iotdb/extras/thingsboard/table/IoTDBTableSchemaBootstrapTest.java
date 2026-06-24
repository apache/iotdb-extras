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

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.rpc.TSStatusCode;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IoTDBTableSchemaBootstrap}: it reads the packaged schema SQL, executes each
 * statement through a table session, rewrites the database name when the configured database
 * differs from the schema default, and tolerates "already exists" failures so a second run is
 * idempotent.
 */
class IoTDBTableSchemaBootstrapTest {

  @Test
  void appliesEveryStatementAndUsesConfiguredDatabaseName() throws Exception {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);

    IoTDBTableConfig config = new IoTDBTableConfig();
    config.setDatabase("tb_custom");

    new IoTDBTableSchemaBootstrap(pool, config).afterPropertiesSet();

    ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
    verify(session, times(4)).executeNonQueryStatement(statements.capture());
    List<String> executed = statements.getAllValues();
    // CREATE DATABASE, USE, CREATE TABLE telemetry, CREATE TABLE entity_attributes.
    assertEquals(4, executed.size());
    assertTrue(
        executed.get(0).contains("CREATE DATABASE IF NOT EXISTS tb_custom"),
        "database name should be rewritten to the configured database: " + executed.get(0));
    assertTrue(executed.get(1).contains("USE tb_custom"), executed.get(1));
    assertTrue(
        executed.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS telemetry")),
        "telemetry table DDL must be applied");
    assertTrue(
        executed.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS entity_attributes")),
        "entity_attributes table DDL must be applied");
    assertContainsInOrder(
        statementContaining(executed, "CREATE TABLE IF NOT EXISTS telemetry"),
        "entity_type",
        "tenant_id",
        "key",
        "entity_id",
        "bool_v");
    assertContainsInOrder(
        statementContaining(executed, "CREATE TABLE IF NOT EXISTS entity_attributes"),
        "time",
        "attribute_scope",
        "entity_type",
        "tenant_id",
        "key",
        "entity_id",
        "bool_v");
    verify(session).close();
  }

  @Test
  void keepsSchemaDefaultDatabaseWhenConfiguredDatabaseMatches() throws Exception {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);

    IoTDBTableConfig config = new IoTDBTableConfig(); // default database = thingsboard

    new IoTDBTableSchemaBootstrap(pool, config).afterPropertiesSet();

    ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
    verify(session, times(4)).executeNonQueryStatement(statements.capture());
    assertTrue(
        statements.getAllValues().get(0).contains("CREATE DATABASE IF NOT EXISTS thingsboard"),
        statements.getAllValues().get(0));
  }

  @Test
  void toleratesAlreadyExistsSoReRunIsIdempotent() throws Exception {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    // Simulate a second bootstrap run: the CREATE TABLE statements fail because the tables already
    // exist. The bootstrap must swallow these and complete without throwing.
    doThrow(new StatementExecutionException("Table 'telemetry' already exists"))
        .when(session)
        .executeNonQueryStatement(org.mockito.ArgumentMatchers.contains("CREATE TABLE"));

    IoTDBTableConfig config = new IoTDBTableConfig();

    // Must not throw despite the already-exists failures.
    new IoTDBTableSchemaBootstrap(pool, config).afterPropertiesSet();

    verify(session, times(4)).executeNonQueryStatement(anyString());
    verify(session).close();
  }

  @Test
  void toleratesAlreadyExistsByStatusCodeEvenWhenMessageDoesNotSayAlreadyExists() throws Exception {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    // Structured-status-code path: the CREATE TABLE failure carries the TABLE_ALREADY_EXISTS status
    // code but a message that does NOT contain "already exist"/"has already been". The bootstrap
    // must still treat it as already-exists (skip, do not propagate), proving idempotency is driven
    // by the status code rather than the wording.
    doThrow(
            new StatementExecutionException(
                new TSStatus(TSStatusCode.TABLE_ALREADY_EXISTS.getStatusCode()).setMessage("boom")))
        .when(session)
        .executeNonQueryStatement(org.mockito.ArgumentMatchers.contains("CREATE TABLE"));

    IoTDBTableConfig config = new IoTDBTableConfig();

    // Must not throw despite the failures, because the status code identifies them as
    // already-exists.
    new IoTDBTableSchemaBootstrap(pool, config).afterPropertiesSet();

    verify(session, times(4)).executeNonQueryStatement(anyString());
    verify(session).close();
  }

  @Test
  void propagatesNonAlreadyExistsFailures() throws Exception {
    ITableSessionPool pool = mock(ITableSessionPool.class);
    ITableSession session = mock(ITableSession.class);
    when(pool.getSession()).thenReturn(session);
    doThrow(new StatementExecutionException("permission denied"))
        .when(session)
        .executeNonQueryStatement(anyString());

    IoTDBTableConfig config = new IoTDBTableConfig();

    assertThrows(
        StatementExecutionException.class,
        () -> new IoTDBTableSchemaBootstrap(pool, config).afterPropertiesSet());
  }

  @Test
  void rejectsMalformedDatabaseNameBeforeOpeningSession() throws Exception {
    // Defense-in-depth: even constructed directly (bypassing @Pattern bean validation), the
    // bootstrap must reject a database name that is not a valid IoTDB identifier before it splices
    // the name into CREATE DATABASE / USE DDL, and must not open a session.
    ITableSessionPool pool = mock(ITableSessionPool.class);

    for (String bad : new String[] {"tb;drop", "tb db", "1tb"}) {
      IoTDBTableConfig config = new IoTDBTableConfig();
      config.setDatabase(bad);
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> new IoTDBTableSchemaBootstrap(pool, config).afterPropertiesSet());
      assertTrue(
          ex.getMessage().contains(bad),
          "rejection message should name the offending database: " + ex.getMessage());
    }
    // No session was ever requested for any of the rejected names.
    verify(pool, never()).getSession();
  }

  private static String statementContaining(List<String> statements, String needle) {
    return statements.stream()
        .filter(statement -> statement.contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing statement containing " + needle));
  }

  private static void assertContainsInOrder(String text, String... needles) {
    int previousIndex = -1;
    for (String needle : needles) {
      int nextIndex = text.indexOf(needle, previousIndex + 1);
      assertTrue(
          nextIndex > previousIndex,
          "expected " + needle + " after index " + previousIndex + " in " + text);
      previousIndex = nextIndex;
    }
  }
}
