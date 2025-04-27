/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iotdb.session")
public class IoTDBSessionProperties {
  private String url;
  private String username;
  private String password;
  private String database;
  private String sql_dialect = "table";
  private Integer max_size = 5;
  private Integer fetch_size = 1024;
  private long query_timeout_in_ms = 60000L;
  private boolean enable_auto_fetch = true;
  private boolean use_ssl = false;
  private int max_retry_count = 60;
  private long wait_to_get_session_timeout_in_msit = 60000L;
  private boolean enable_compression = false;
  private long retry_interval_in_ms = 500L;

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getDatabase() {
    return database;
  }

  public void setDatabase(String database) {
    this.database = database;
  }

  public String getSql_dialect() {
    return sql_dialect;
  }

  public void setSql_dialect(String sql_dialect) {
    this.sql_dialect = sql_dialect;
  }

  public Integer getMax_size() {
    return max_size;
  }

  public void setMax_size(Integer max_size) {
    this.max_size = max_size;
  }

  public Integer getFetch_size() {
    return fetch_size;
  }

  public void setFetch_size(Integer fetch_size) {
    this.fetch_size = fetch_size;
  }

  public Long getQuery_timeout_in_ms() {
    return query_timeout_in_ms;
  }

  public void setQuery_timeout_in_ms(Long query_timeout_in_ms) {
    this.query_timeout_in_ms = query_timeout_in_ms;
  }

  public Boolean getEnable_auto_fetch() {
    return enable_auto_fetch;
  }

  public void setEnable_auto_fetch(Boolean enable_auto_fetch) {
    this.enable_auto_fetch = enable_auto_fetch;
  }

  public Boolean getUse_ssl() {
    return use_ssl;
  }

  public void setUse_ssl(Boolean use_ssl) {
    this.use_ssl = use_ssl;
  }

  public Integer getMax_retry_count() {
    return max_retry_count;
  }

  public void setMax_retry_count(Integer max_retry_count) {
    this.max_retry_count = max_retry_count;
  }

  public void setQuery_timeout_in_ms(long query_timeout_in_ms) {
    this.query_timeout_in_ms = query_timeout_in_ms;
  }

  public boolean isEnable_auto_fetch() {
    return enable_auto_fetch;
  }

  public void setEnable_auto_fetch(boolean enable_auto_fetch) {
    this.enable_auto_fetch = enable_auto_fetch;
  }

  public boolean isUse_ssl() {
    return use_ssl;
  }

  public void setUse_ssl(boolean use_ssl) {
    this.use_ssl = use_ssl;
  }

  public void setMax_retry_count(int max_retry_count) {
    this.max_retry_count = max_retry_count;
  }

  public long getWait_to_get_session_timeout_in_msit() {
    return wait_to_get_session_timeout_in_msit;
  }

  public void setWait_to_get_session_timeout_in_msit(long wait_to_get_session_timeout_in_msit) {
    this.wait_to_get_session_timeout_in_msit = wait_to_get_session_timeout_in_msit;
  }

  public boolean isEnable_compression() {
    return enable_compression;
  }

  public void setEnable_compression(boolean enable_compression) {
    this.enable_compression = enable_compression;
  }

  public long getRetry_interval_in_ms() {
    return retry_interval_in_ms;
  }

  public void setRetry_interval_in_ms(long retry_interval_in_ms) {
    this.retry_interval_in_ms = retry_interval_in_ms;
  }
}
