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
public class IoTDBRelationalOptions implements Serializable {

  private static final long serialVersionUID = 1L;

  public static final String IDENTIFIER = "iotdb-relational";

  public static final ConfigOption<String> NODE_URLS =
      ConfigOptions.key("nodeUrls").stringType().noDefaultValue();

  public static final ConfigOption<String> USER =
      ConfigOptions.key("user").stringType().defaultValue("root");

  public static final ConfigOption<String> PASSWORD =
      ConfigOptions.key("password").stringType().defaultValue("root");

  public static final ConfigOption<String> DATABASE =
      ConfigOptions.key("database").stringType().noDefaultValue();

  public static final ConfigOption<String> TABLE =
      ConfigOptions.key("table").stringType().noDefaultValue();

  public static final ConfigOption<String> DEFAULT_DATABASE =
      ConfigOptions.key("default-database").stringType().defaultValue("public");

  public static final ConfigOption<String> TIME_COLUMN =
      ConfigOptions.key("time-column").stringType().noDefaultValue();

  public static final ConfigOption<String> TAG_COLUMNS =
      ConfigOptions.key("tag-columns").stringType().defaultValue("");

  public static final ConfigOption<String> ATTRIBUTE_COLUMNS =
      ConfigOptions.key("attribute-columns").stringType().defaultValue("");

  private final List<String> nodeUrls;
  private final String username;
  private final String password;
  private final String database;
  private final String table;
  private final String defaultDatabase;
  private final String timeColumn;
  private final List<String> tagColumns;
  private final List<String> attributeColumns;

  private IoTDBRelationalOptions(Builder builder) {
    this.nodeUrls = builder.nodeUrls;
    this.username = builder.username;
    this.password = builder.password;
    this.database = builder.database;
    this.table = builder.table;
    this.defaultDatabase = builder.defaultDatabase;
    this.timeColumn = builder.timeColumn;
    this.tagColumns = builder.tagColumns;
    this.attributeColumns = builder.attributeColumns;
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

  /** @return the configured IoTDB time-column name, or {@code null} when unspecified. */
  public String getTimeColumn() {
    return timeColumn;
  }

  /** @return configured IoTDB TAG column names. */
  public List<String> getTagColumns() {
    return tagColumns;
  }

  /** @return configured IoTDB ATTRIBUTE column names. */
  public List<String> getAttributeColumns() {
    return attributeColumns;
  }

  /**
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder of {@link IoTDBRelationalOptions}. */
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

    /**
     * @return the built options
     */
    public IoTDBRelationalOptions build() {
      return new IoTDBRelationalOptions(this);
    }
  }
}
