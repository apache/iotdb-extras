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

import org.apache.iotdb.isession.SessionConfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties(prefix = "iotdb.session")
public class IoTDBSessionProperties {
  private String node_urls;
  private String username;
  private String password;
  private String database;
  private String sql_dialect = "table";
  private Integer max_size = 5;
  private Integer fetch_size = 1024;
  private long query_timeout_in_ms = 60000L;
  private boolean enable_auto_fetch = true;
  private Integer max_retry_count = 60;
  private long wait_to_get_session_timeout_in_ms = 60000L;
  private boolean enable_compression = false;
  private long retry_interval_in_ms = SessionConfig.RETRY_INTERVAL_IN_MS;
  private boolean use_ssl = false;
  private String trust_store;
  private String trust_store_pwd;
  private Integer connection_timeout_in_ms;
  private ZoneId zone_id;
  private Integer thrift_default_buffer_size = 1024;
  private Integer thrift_max_frame_size = 67108864;
  private boolean enable_redirection;
  private boolean enable_records_auto_convert_tablet =
      SessionConfig.DEFAULT_RECORDS_AUTO_CONVERT_TABLET;

  public String getNode_urls() {
    return node_urls;
  }

  public void setNode_urls(String node_urls) {
    this.node_urls = node_urls;
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

  public long getQuery_timeout_in_ms() {
    return query_timeout_in_ms;
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

  public Integer getMax_retry_count() {
    return max_retry_count;
  }

  public void setMax_retry_count(Integer max_retry_count) {
    this.max_retry_count = max_retry_count;
  }

  public long getWait_to_get_session_timeout_in_ms() {
    return wait_to_get_session_timeout_in_ms;
  }

  public void setWait_to_get_session_timeout_in_ms(long wait_to_get_session_timeout_in_ms) {
    this.wait_to_get_session_timeout_in_ms = wait_to_get_session_timeout_in_ms;
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

  public String getTrust_store() {
    return trust_store;
  }

  public void setTrust_store(String trust_store) {
    this.trust_store = trust_store;
  }

  public String getTrust_store_pwd() {
    return trust_store_pwd;
  }

  public void setTrust_store_pwd(String trust_store_pwd) {
    this.trust_store_pwd = trust_store_pwd;
  }

  public Integer getConnection_timeout_in_ms() {
    return connection_timeout_in_ms;
  }

  public void setConnection_timeout_in_ms(Integer connection_timeout_in_ms) {
    this.connection_timeout_in_ms = connection_timeout_in_ms;
  }

  public ZoneId getZone_id() {
    return zone_id;
  }

  public void setZone_id(ZoneId zone_id) {
    this.zone_id = zone_id;
  }

  public Integer getThrift_default_buffer_size() {
    return thrift_default_buffer_size;
  }

  public void setThrift_default_buffer_size(Integer thrift_default_buffer_size) {
    this.thrift_default_buffer_size = thrift_default_buffer_size;
  }

  public Integer getThrift_max_frame_size() {
    return thrift_max_frame_size;
  }

  public void setThrift_max_frame_size(Integer thrift_max_frame_size) {
    this.thrift_max_frame_size = thrift_max_frame_size;
  }

  public boolean isEnable_redirection() {
    return enable_redirection;
  }

  public void setEnable_redirection(boolean enable_redirection) {
    this.enable_redirection = enable_redirection;
  }

  public boolean isEnable_records_auto_convert_tablet() {
    return enable_records_auto_convert_tablet;
  }

  public void setEnable_records_auto_convert_tablet(boolean enable_records_auto_convert_tablet) {
    this.enable_records_auto_convert_tablet = enable_records_auto_convert_tablet;
  }
}
