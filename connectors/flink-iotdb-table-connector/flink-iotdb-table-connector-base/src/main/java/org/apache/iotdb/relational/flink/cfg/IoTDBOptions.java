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

package org.apache.iotdb.relational.flink.cfg;

import org.apache.iotdb.relational.flink.utils.TimestampPrecision;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Options and Flink table option keys of the IoTDB relational (table model) Flink connector.
 *
 * <p>The static {@link org.apache.flink.configuration.ConfigOption} constants define the keys used
 * in the Flink table DDL; the instance fields hold the resolved options passed to the runtime.
 */
public class IoTDBOptions implements Serializable {

  private static final long serialVersionUID = 1L;

  public static final String IDENTIFIER = "iotdb-relational";

  public static final ConfigOption<String> NODE_URLS =
      ConfigOptions.key("iotdb.node-urls").stringType().noDefaultValue();

  public static final ConfigOption<String> USER =
      ConfigOptions.key("iotdb.user").stringType().defaultValue("root");

  public static final ConfigOption<String> PASSWORD =
      ConfigOptions.key("iotdb.password").stringType().defaultValue("root");

  public static final ConfigOption<String> DATABASE =
      ConfigOptions.key("iotdb.database").stringType().noDefaultValue();

  public static final ConfigOption<String> TABLE =
      ConfigOptions.key("iotdb.table").stringType().noDefaultValue();

  public static final ConfigOption<String> DEFAULT_DATABASE =
      ConfigOptions.key("iotdb.default-database").stringType().defaultValue("public");

  public static final ConfigOption<String> TIME_COLUMN =
      ConfigOptions.key("iotdb.time-column").stringType().noDefaultValue();

  public static final ConfigOption<String> TAG_COLUMNS =
      ConfigOptions.key("iotdb.tag-columns").stringType().defaultValue("");

  public static final ConfigOption<String> ATTRIBUTE_COLUMNS =
      ConfigOptions.key("iotdb.attribute-columns").stringType().defaultValue("");

  public static final ConfigOption<Boolean> LOOKUP_ASYNC =
      ConfigOptions.key("iotdb.lookup.async").booleanType().defaultValue(false);

  public static final ConfigOption<Integer> LOOKUP_THREAD_SIZE =
      ConfigOptions.key("iotdb.lookup.thread-size").intType().defaultValue(5);

  public static final ConfigOption<ScanMode> SCAN_MODE =
      ConfigOptions.key("iotdb.scan.mode")
          .enumType(ScanMode.class)
          .defaultValue(ScanMode.SNAPSHOT);

  public static final ConfigOption<String> CDC_MODE =
      ConfigOptions.key("iotdb.cdc.mode").stringType().defaultValue("live");

  public static final ConfigOption<String> CDC_START_TIME =
      ConfigOptions.key("iotdb.cdc.start-time").stringType().noDefaultValue();

  public static final ConfigOption<String> CDC_END_TIME =
      ConfigOptions.key("iotdb.cdc.end-time").stringType().noDefaultValue();

  public static final ConfigOption<Long> CDC_POLL_TIMEOUT_MS =
      ConfigOptions.key("iotdb.cdc.poll-timeout-ms").longType().defaultValue(1000L);

  public static final ConfigOption<Boolean> CDC_AUTO_COMMIT =
      ConfigOptions.key("iotdb.cdc.auto-commit").booleanType().defaultValue(true);

  public static final ConfigOption<TimestampPrecision> TIMESTAMP_PRECISION =
      ConfigOptions.key("iotdb.timestamp-precision")
          .enumType(TimestampPrecision.class)
          .defaultValue(TimestampPrecision.MS);

  private static final String CDC_TOPIC_PREFIX = "flink_iotdb_table_";

  /** Scan mode of the table source. */
  public enum ScanMode {
    SNAPSHOT,
    CDC
  }

  private final List<String> nodeUrls;
  private final String username;
  private final String password;
  private final String database;
  private final String table;
  private final String defaultDatabase;
  private final String timeColumn;
  private final List<String> tagColumns;
  private final List<String> attributeColumns;
  private final boolean lookupAsync;
  private final int lookupThreadSize;
  private final ScanMode scanMode;
  private final String cdcMode;
  private final String cdcStartTime;
  private final String cdcEndTime;
  private final long cdcPollTimeoutMs;
  private final boolean cdcAutoCommit;
  private final TimestampPrecision timestampPrecision;

  private IoTDBOptions(Builder builder) {
    this.nodeUrls = builder.nodeUrls;
    this.username = builder.username;
    this.password = builder.password;
    this.database = builder.database;
    this.table = builder.table;
    this.defaultDatabase = builder.defaultDatabase;
    this.timeColumn = builder.timeColumn;
    this.tagColumns = builder.tagColumns;
    this.attributeColumns = builder.attributeColumns;
    this.lookupAsync = builder.lookupAsync;
    this.lookupThreadSize = builder.lookupThreadSize;
    this.scanMode = builder.scanMode;
    this.cdcMode = builder.cdcMode;
    this.cdcStartTime = builder.cdcStartTime;
    this.cdcEndTime = builder.cdcEndTime;
    this.cdcPollTimeoutMs = builder.cdcPollTimeoutMs;
    this.cdcAutoCommit = builder.cdcAutoCommit;
    this.timestampPrecision = builder.timestampPrecision;
  }

  /**
   * @return IoTDB node urls, e.g. {@code ["127.0.0.1:6667"]}.
   */
  public List<String> getNodeUrls() {
    return nodeUrls;
  }

  /**
   * @return IoTDB username.
   */
  public String getUsername() {
    return username;
  }

  /**
   * @return IoTDB password.
   */
  public String getPassword() {
    return password;
  }

  /**
   * @return IoTDB database name.
   */
  public String getDatabase() {
    return database;
  }

  /**
   * @return IoTDB table name.
   */
  public String getTable() {
    return table;
  }

  /**
   * @return the default database used by the catalog.
   */
  public String getDefaultDatabase() {
    return defaultDatabase;
  }

  /**
   * @return the configured IoTDB {@code iotdb.time-column} name, or {@code null} when unspecified.
   */
  public String getTimeColumn() {
    return timeColumn;
  }

  /**
   * @return configured IoTDB TAG column names.
   */
  public List<String> getTagColumns() {
    return tagColumns;
  }

  /**
   * @return configured IoTDB ATTRIBUTE column names.
   */
  public List<String> getAttributeColumns() {
    return attributeColumns;
  }

  /**
   * @return the timestamp precision of the target IoTDB server.
   */
  public TimestampPrecision getTimestampPrecision() {
    return timestampPrecision;
  }

  /**
   * @return whether an asynchronous lookup function is used for lookup joins.
   */
  public boolean isLookupAsync() {
    return lookupAsync;
  }

  /**
   * @return the number of concurrent lookup query threads, also the lookup session pool size.
   */
  public int getLookupThreadSize() {
    return lookupThreadSize;
  }

  /**
   * @return the scan mode of the table source.
   */
  public ScanMode getScanMode() {
    return scanMode;
  }

  /**
   * @return whether the CDC (subscription) source is used instead of the bounded snapshot scan.
   */
  public boolean isCdc() {
    return scanMode == ScanMode.CDC;
  }

  /**
   * @return the CDC topic name, derived from the connector marker and the database/table names so
   *     the topic origin is recognizable.
   */
  public String getCdcTopic() {
    return CDC_TOPIC_PREFIX + sanitize(database) + "_" + sanitize(table);
  }

  /**
   * @return the CDC consumer group id, derived from the topic name.
   */
  public String getCdcConsumerGroup() {
    return getCdcTopic() + "_group";
  }

  /**
   * @return the CDC topic mode, e.g. {@code live} or {@code snapshot}.
   */
  public String getCdcMode() {
    return cdcMode;
  }

  /**
   * @return the CDC history start time, or {@code null} when unset.
   */
  public String getCdcStartTime() {
    return cdcStartTime;
  }

  /**
   * @return the CDC history end time, or {@code null} when unset.
   */
  public String getCdcEndTime() {
    return cdcEndTime;
  }

  /**
   * @return the CDC poll timeout in milliseconds.
   */
  public long getCdcPollTimeoutMs() {
    return cdcPollTimeoutMs;
  }

  /**
   * @return whether the CDC consumer commits offsets automatically.
   */
  public boolean isCdcAutoCommit() {
    return cdcAutoCommit;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
  }

  /**
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder of {@link IoTDBOptions}. */
  public static class Builder {

    private List<String> nodeUrls;
    private String username;
    private String password;
    private String database;
    private String table;
    private String defaultDatabase = "public";
    private String timeColumn;
    private List<String> tagColumns = Collections.emptyList();
    private List<String> attributeColumns = Collections.emptyList();
    private boolean lookupAsync = false;
    private int lookupThreadSize = 5;
    private ScanMode scanMode = ScanMode.SNAPSHOT;
    private String cdcMode = "live";
    private String cdcStartTime;
    private String cdcEndTime;
    private long cdcPollTimeoutMs = 1000L;
    private boolean cdcAutoCommit = true;
    private TimestampPrecision timestampPrecision = TimestampPrecision.MS;

    public Builder withNodeUrls(List<String> nodeUrls) {
      this.nodeUrls = nodeUrls;
      return this;
    }

    public Builder withUsername(String username) {
      this.username = username;
      return this;
    }

    public Builder withPassword(String password) {
      this.password = password;
      return this;
    }

    public Builder withDatabase(String database) {
      this.database = database;
      return this;
    }

    public Builder withTable(String table) {
      this.table = table;
      return this;
    }

    public Builder withDefaultDatabase(String defaultDatabase) {
      this.defaultDatabase = defaultDatabase;
      return this;
    }

    public Builder withTimeColumn(String timeColumn) {
      this.timeColumn = timeColumn;
      return this;
    }

    public Builder withTagColumns(List<String> tagColumns) {
      this.tagColumns = tagColumns;
      return this;
    }

    public Builder withAttributeColumns(List<String> attributeColumns) {
      this.attributeColumns = attributeColumns;
      return this;
    }

    public Builder withLookupAsync(boolean lookupAsync) {
      this.lookupAsync = lookupAsync;
      return this;
    }

    public Builder withLookupThreadSize(int lookupThreadSize) {
      this.lookupThreadSize = lookupThreadSize;
      return this;
    }

    public Builder withScanMode(ScanMode scanMode) {
      this.scanMode = scanMode;
      return this;
    }

    public Builder withCdcMode(String cdcMode) {
      this.cdcMode = cdcMode;
      return this;
    }

    public Builder withCdcStartTime(String cdcStartTime) {
      this.cdcStartTime = cdcStartTime;
      return this;
    }

    public Builder withCdcEndTime(String cdcEndTime) {
      this.cdcEndTime = cdcEndTime;
      return this;
    }

    public Builder withCdcPollTimeoutMs(long cdcPollTimeoutMs) {
      this.cdcPollTimeoutMs = cdcPollTimeoutMs;
      return this;
    }

    public Builder withCdcAutoCommit(boolean cdcAutoCommit) {
      this.cdcAutoCommit = cdcAutoCommit;
      return this;
    }

    public Builder withTimestampPrecision(TimestampPrecision timestampPrecision) {
      this.timestampPrecision = timestampPrecision;
      return this;
    }

    /**
     * @return the built options
     */
    public IoTDBOptions build() {
      return new IoTDBOptions(this);
    }
  }
}
