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

package org.apache.iotdb.session;

import org.apache.iotdb.config.IoTDBSessionProperties;
import org.apache.iotdb.isession.pool.ISessionPool;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@AutoConfiguration
@ConditionalOnClass({SessionPool.class, TableSessionPoolBuilder.class})
@EnableConfigurationProperties(IoTDBSessionProperties.class)
public class IoTDBSessionPool {

  private final IoTDBSessionProperties properties;

  public IoTDBSessionPool(IoTDBSessionProperties properties) {
    this.properties = properties;
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ITableSessionPool.class)
  public ITableSessionPool tableSessionPool() {
    return new TableSessionPoolBuilder()
        .nodeUrls(nodeUrls())
        .user(properties.getUsername())
        .password(properties.getPassword())
        .database(properties.getDatabase())
        .maxSize(properties.getMax_size())
        .fetchSize(properties.getFetch_size())
        .enableAutoFetch(properties.isEnable_auto_fetch())
        .useSSL(properties.isUse_ssl())
        .queryTimeoutInMs(properties.getQuery_timeout_in_ms())
        .maxRetryCount(properties.getMax_retry_count())
        .waitToGetSessionTimeoutInMs(properties.getWait_to_get_session_timeout_in_ms())
        .enableThriftCompression(properties.isEnable_compression())
        .retryIntervalInMs(properties.getRetry_interval_in_ms())
        .trustStore(properties.getTrust_store())
        .trustStorePwd(properties.getTrust_store_pwd())
        .connectionTimeoutInMs(properties.getConnection_timeout_in_ms())
        .zoneId(properties.getZone_id())
        .thriftDefaultBufferSize(properties.getThrift_default_buffer_size())
        .thriftMaxFrameSize(properties.getThrift_max_frame_size())
        .enableRedirection(properties.isEnable_redirection())
        .build();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ISessionPool.class)
  public ISessionPool treeSessionPool() {
    return new SessionPool.Builder()
        .nodeUrls(nodeUrls())
        .user(properties.getUsername())
        .password(properties.getPassword())
        .maxSize(properties.getMax_size())
        .fetchSize(properties.getFetch_size())
        .enableAutoFetch(properties.isEnable_auto_fetch())
        .useSSL(properties.isUse_ssl())
        .queryTimeoutInMs(properties.getQuery_timeout_in_ms())
        .maxRetryCount(properties.getMax_retry_count())
        .waitToGetSessionTimeoutInMs(properties.getWait_to_get_session_timeout_in_ms())
        .enableThriftRpcCompaction(properties.isEnable_compression())
        .retryIntervalInMs(properties.getRetry_interval_in_ms())
        .trustStore(properties.getTrust_store())
        .trustStorePwd(properties.getTrust_store_pwd())
        .connectionTimeoutInMs(properties.getConnection_timeout_in_ms())
        .zoneId(properties.getZone_id())
        .thriftDefaultBufferSize(properties.getThrift_default_buffer_size())
        .thriftMaxFrameSize(properties.getThrift_max_frame_size())
        .enableRedirection(properties.isEnable_redirection())
        .enableRecordsAutoConvertTablet(properties.isEnable_records_auto_convert_tablet())
        .build();
  }

  private List<String> nodeUrls() {
    String configuredUrls = properties.getNode_urls();
    if (configuredUrls == null || configuredUrls.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "iotdb.session.node-urls must contain at least one host:port");
    }
    List<String> urls =
        Arrays.stream(configuredUrls.split(";", -1)).map(String::trim).collect(Collectors.toList());
    if (urls.stream().anyMatch(String::isEmpty)) {
      throw new IllegalArgumentException("iotdb.session.node-urls contains an empty endpoint");
    }
    return urls;
  }
}
