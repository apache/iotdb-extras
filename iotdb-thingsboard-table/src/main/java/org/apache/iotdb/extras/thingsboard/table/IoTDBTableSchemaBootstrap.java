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
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.rpc.TSStatusCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Idempotent startup bootstrap for the IoTDB Table Mode schema.
 *
 * <p>On a fresh IoTDB the {@code telemetry} / {@code entity_attributes} tables (and their database)
 * do not exist, so the very first write would fail. This initializer runs once the session pool
 * bean is up, reads {@code schema-iotdb-table.sql} from the classpath, and executes its statements.
 * Every DDL statement uses {@code CREATE ... IF NOT EXISTS}, so a re-run returns SUCCESS without
 * throwing. As defense-in-depth for racy or partial schema states, an "already exists" failure is
 * also recognized by its structured IoTDB status code (with a message-substring fallback) and
 * tolerated rather than propagated, keeping the bootstrap idempotent.
 *
 * <p>Gated behind {@code iotdb.schema.bootstrap} (default {@code true}) so operators who manage the
 * schema out-of-band can disable it; see the module README.
 */
@Slf4j
public class IoTDBTableSchemaBootstrap implements InitializingBean {

  static final String SCHEMA_RESOURCE = "schema-iotdb-table.sql";
  private static final String DEFAULT_SCHEMA_DATABASE = "thingsboard";

  /**
   * IoTDB identifier rule (letter or underscore first, then letters, digits or underscores). The
   * database name is spliced verbatim into {@code CREATE DATABASE} / {@code USE} DDL, so it is
   * validated here as defense-in-depth in addition to the {@code @Pattern} bean-validation
   * constraint on {@link IoTDBTableConfig#getDatabase()} (the bootstrap can be constructed
   * directly, bypassing bean validation).
   */
  private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

  private final ITableSessionPool tableSessionPool;
  private final IoTDBTableConfig config;

  public IoTDBTableSchemaBootstrap(ITableSessionPool tableSessionPool, IoTDBTableConfig config) {
    this.tableSessionPool = Objects.requireNonNull(tableSessionPool, "tableSessionPool");
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    String database = requireValidDatabaseName(config.getDatabase());
    String schema = loadSchema(database);
    int created = 0;
    int skipped = 0;
    try (ITableSession session = tableSessionPool.getSession()) {
      for (String statement : schema.split(";")) {
        String trimmed = statement.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        try {
          session.executeNonQueryStatement(trimmed);
          created++;
        } catch (Exception e) {
          if (isAlreadyExists(e)) {
            skipped++;
            log.debug("IoTDB Table Mode schema object already exists, skipping: {}", trimmed, e);
          } else {
            throw e;
          }
        }
      }
    }
    log.info(
        "IoTDB Table Mode schema bootstrap complete for database '{}': {} statement(s) applied, "
            + "{} already-present statement(s) skipped",
        database,
        created,
        skipped);
  }

  private static String requireValidDatabaseName(String database) {
    if (database == null || !DATABASE_NAME_PATTERN.matcher(database).matches()) {
      throw new IllegalStateException(
          "Invalid IoTDB database name for schema bootstrap: '"
              + database
              + "'. It must be a valid IoTDB identifier (a letter or underscore followed by letters,"
              + " digits or underscores) because it is spliced into CREATE DATABASE / USE DDL.");
    }
    return database;
  }

  private String loadSchema(String database) throws IOException {
    try (InputStream stream =
        IoTDBTableSchemaBootstrap.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException(
            "IoTDB Table Mode schema resource not found on classpath: " + SCHEMA_RESOURCE);
      }
      String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      // Strip block and line comments so split-on-';' never executes a comment fragment.
      schema = schema.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "");
      if (!DEFAULT_SCHEMA_DATABASE.equals(database)) {
        schema =
            schema
                .replace(
                    "CREATE DATABASE IF NOT EXISTS " + DEFAULT_SCHEMA_DATABASE + ";",
                    "CREATE DATABASE IF NOT EXISTS " + database + ";")
                .replace("USE " + DEFAULT_SCHEMA_DATABASE + ";", "USE " + database + ";");
      }
      return schema;
    }
  }

  private static boolean isAlreadyExists(Throwable t) {
    for (Throwable cause = t; cause != null; cause = cause.getCause()) {
      // Primary signal: structured IoTDB status code (locale- and wording-independent). The DDL
      // itself uses CREATE ... IF NOT EXISTS, so the normal re-run path returns SUCCESS without
      // throwing; this code path is the defense-in-depth fallback for racy/partial schema states.
      if (cause instanceof StatementExecutionException see) {
        int code = see.getStatusCode();
        if (code == TSStatusCode.TABLE_ALREADY_EXISTS.getStatusCode()
            || code == TSStatusCode.DATABASE_ALREADY_EXISTS.getStatusCode()
            || code == TSStatusCode.COLUMN_ALREADY_EXISTS.getStatusCode()) {
          return true;
        }
      }
      // Last-resort fallback for non-typed / wrapped exceptions that carry no status code.
      String message = cause.getMessage();
      if (message != null) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("already exist") || lower.contains("has already been")) {
          return true;
        }
      }
    }
    return false;
  }
}
