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

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@ConditionalOnClass({IoTDBSessionProperties.class})
@EnableConfigurationProperties(IoTDBSessionProperties.class)
public class IoTDBSessionPool {

  private final IoTDBSessionProperties properties;
  private ITableSessionPool tableSessionPool;
  private ISessionPool treeSessionPool;

  public IoTDBSessionPool(IoTDBSessionProperties properties) {
    this.properties = properties;
  }

  @Bean
  public ITableSessionPool tableSessionPool() {
    if (tableSessionPool == null) {
      synchronized (IoTDBSessionPool.class) {
        if (tableSessionPool == null) {
          tableSessionPool =
              new TableSessionPoolBuilder()
                  .nodeUrls(Arrays.asList(properties.getUrl().split(";")))
                  .user(properties.getUsername())
                  .password(properties.getPassword())
                  .database(properties.getDatabase())
                  .maxSize(properties.getMax_size())
                  .fetchSize(properties.getFetch_size())
                  .enableAutoFetch(properties.getEnable_auto_fetch())
                  .useSSL(properties.getUse_ssl())
                  .queryTimeoutInMs(properties.getQuery_timeout_in_ms())
                  .maxRetryCount(properties.getMax_retry_count())
                  .waitToGetSessionTimeoutInMs(properties.getQuery_timeout_in_ms())
                  .enableCompression(properties.isEnable_compression())
                  .retryIntervalInMs(properties.getRetry_interval_in_ms())
                  .build();
        }
      }
    }
    return tableSessionPool;
  }

  @Bean
  public ISessionPool treeSessionPool() {
    if (treeSessionPool == null) {
      synchronized (IoTDBSessionPool.class) {
        if (treeSessionPool == null) {
          treeSessionPool =
              new SessionPool.Builder()
                  .nodeUrls(Arrays.asList(properties.getUrl().split(";")))
                  .user(properties.getUsername())
                  .password(properties.getPassword())
                  .maxSize(properties.getMax_size())
                  .fetchSize(properties.getFetch_size())
                  .enableAutoFetch(properties.getEnable_auto_fetch())
                  .useSSL(properties.getUse_ssl())
                  .queryTimeoutInMs(properties.getQuery_timeout_in_ms())
                  .maxRetryCount(properties.getMax_retry_count())
                  .waitToGetSessionTimeoutInMs(properties.getQuery_timeout_in_ms())
                  .enableCompression(properties.isEnable_compression())
                  .retryIntervalInMs(properties.getRetry_interval_in_ms())
                  .build();
        }
      }
    }
    return treeSessionPool;
  }
}
