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
import org.apache.iotdb.isession.SessionConfig;
import org.apache.iotdb.isession.pool.ISessionPool;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.session.pool.SessionPool;

import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IoTDBSessionPoolTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(IoTDBSessionPool.class))
          .withPropertyValues("iotdb.session.enable-auto-fetch=false");

  private final ApplicationContextRunner autoDiscoveryContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(AutoConfigurationApplication.class)
          .withPropertyValues("iotdb.session.enable-auto-fetch=false");

  @Test
  public void createsBothPoolsWithDefaults() {
    contextRunner.run(
        context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(ISessionPool.class)
              .hasSingleBean(ITableSessionPool.class);
          IoTDBSessionProperties properties = context.getBean(IoTDBSessionProperties.class);
          assertThat(properties.getConnection_timeout_in_ms()).isZero();
          assertThat(properties.getFetch_size()).isEqualTo(SessionConfig.DEFAULT_FETCH_SIZE);
          assertDefaultPoolParameters(context.getBean(ISessionPool.class));
          assertThat(context.getBean(ITableSessionPool.class))
              .extracting("sessionPool")
              .isInstanceOfSatisfying(SessionPool.class, this::assertDefaultPoolParameters);
        });
  }

  private void assertDefaultPoolParameters(ISessionPool pool) {
    assertThat(pool.getFetchSize()).isEqualTo(SessionConfig.DEFAULT_FETCH_SIZE);
    assertThat(pool)
        .extracting(
            "connectionTimeoutInMs",
            "retryIntervalInMs",
            "enableRecordsAutoConvertTablet",
            "enableRedirection",
            "enableThriftCompression",
            "enableIoTDBRpcCompression")
        .containsExactly(0, 500L, true, false, false, true);
  }

  @Test
  public void discoversAutoConfigurationFromImportsFile() {
    autoDiscoveryContextRunner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(IoTDBSessionPool.class)
                .hasSingleBean(ISessionPool.class)
                .hasSingleBean(ITableSessionPool.class));
  }

  @Test
  public void bindsLegacyAndCanonicalPropertyNames() {
    contextRunner
        .withPropertyValues(
            "iotdb.session.node_urls=localhost:6667",
            "iotdb.session.connection-timeout-in-ms=2500",
            "iotdb.session.max_size=3")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              IoTDBSessionProperties properties = context.getBean(IoTDBSessionProperties.class);
              assertThat(properties.getNode_urls()).isEqualTo("localhost:6667");
              assertThat(properties.getConnection_timeout_in_ms()).isEqualTo(2500);
              assertThat(properties.getMax_size()).isEqualTo(3);
            });
  }

  @Test
  public void appliesAllCanonicalParametersToBothPools() {
    assertParameterBinding(UnaryOperator.identity());
  }

  @Test
  public void appliesAllUnderscoreParametersToBothPools() {
    assertParameterBinding(name -> name.replace('-', '_'));
  }

  @Test
  public void appliesAllCamelCaseParametersToBothPools() {
    assertParameterBinding(
        name -> {
          StringBuilder result = new StringBuilder();
          boolean capitalize = false;
          for (char character : name.toCharArray()) {
            if (character == '-') {
              capitalize = true;
            } else {
              result.append(capitalize ? Character.toUpperCase(character) : character);
              capitalize = false;
            }
          }
          return result.toString();
        });
  }

  private void assertParameterBinding(UnaryOperator<String> propertyName) {
    String[] settings = {
      "node-urls= localhost:16667 ; localhost:16668 ",
      "username=test-user",
      "password=test-password",
      "database=test_database",
      "sql-dialect=tree",
      "max-size=3",
      "fetch-size=2048",
      "query-timeout-in-ms=12000",
      "enable-auto-fetch=false",
      "max-retry-count=4",
      "wait-to-get-session-timeout-in-ms=2300",
      "enable-compression=true",
      "retry-interval-in-ms=200",
      "use-ssl=true",
      "trust-store=test-trust-store.jks",
      "trust-store-pwd=test-trust-store-password",
      "connection-timeout-in-ms=2500",
      "zone-id=Asia/Shanghai",
      "thrift-default-buffer-size=4096",
      "thrift-max-frame-size=33554432",
      "enable-redirection=true",
      "enable-records-auto-convert-tablet=false"
    };
    String[] configuredProperties =
        Arrays.stream(settings)
            .map(
                setting -> {
                  int separator = setting.indexOf('=');
                  return "iotdb.session."
                      + propertyName.apply(setting.substring(0, separator))
                      + setting.substring(separator);
                })
            .toArray(String[]::new);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IoTDBSessionPool.class))
        .withPropertyValues(configuredProperties)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(IoTDBSessionProperties.class).getSql_dialect())
                  .isEqualTo("tree");
              assertConfiguredPoolParameters(
                  (SessionPool) context.getBean(ISessionPool.class), false);
              assertThat(context.getBean(ITableSessionPool.class))
                  .extracting("sessionPool")
                  .isInstanceOfSatisfying(
                      SessionPool.class, pool -> assertConfiguredPoolParameters(pool, true));
            });
  }

  private void assertConfiguredPoolParameters(SessionPool pool, boolean table) {
    assertThat(pool.getUser()).isEqualTo("test-user");
    assertThat(pool.getPassword()).isEqualTo("test-password");
    assertThat(pool.getMaxSize()).isEqualTo(3);
    assertThat(pool.getFetchSize()).isEqualTo(2048);
    assertThat(pool.getQueryTimeout()).isEqualTo(12000L);
    assertThat(pool.getWaitToGetSessionTimeoutInMs()).isEqualTo(2300L);
    assertThat(pool.getConnectionTimeoutInMs()).isEqualTo(2500);
    assertThat(pool.getZoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    assertThat(pool.isEnableThriftCompression()).isTrue();
    assertThat(pool.isEnableRedirection()).isTrue();
    // TableSessionPool has no configuration getters; inspect its underlying pool without opening
    // a connection. The remaining SessionPool settings also have no public getters.
    assertThat(pool)
        .extracting(
            "nodeUrls",
            "database",
            "sqlDialect",
            "enableAutoFetch",
            "maxRetryCount",
            "retryIntervalInMs",
            "useSSL",
            "trustStore",
            "trustStorePwd",
            "thriftDefaultBufferSize",
            "thriftMaxFrameSize",
            "enableRecordsAutoConvertTablet",
            "enableIoTDBRpcCompression")
        .containsExactly(
            List.of("localhost:16667", "localhost:16668"),
            table ? "test_database" : null,
            table ? "table" : "tree",
            false,
            4,
            200L,
            true,
            "test-trust-store.jks",
            "test-trust-store-password",
            4096,
            33554432,
            table,
            true);
  }

  @Test
  public void preservesSpecialTimeoutAndRetryValues() {
    for (long timeout : new long[] {-1, 0}) {
      contextRunner
          .withPropertyValues(
              "iotdb.session.query-timeout-in-ms=" + timeout,
              "iotdb.session.max-retry-count=0",
              "iotdb.session.retry-interval-in-ms=0")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ISessionPool.class))
                    .extracting("queryTimeoutInMs", "maxRetryCount", "retryIntervalInMs")
                    .containsExactly(timeout, 0, 0L);
                assertThat(context.getBean(ITableSessionPool.class))
                    .extracting("sessionPool")
                    .extracting("queryTimeoutInMs", "maxRetryCount", "retryIntervalInMs")
                    .containsExactly(timeout, 0, 0L);
              });
    }
  }

  @Test
  public void acceptsWhitespaceAroundEndpoints() {
    contextRunner
        .withPropertyValues("iotdb.session.node-urls= localhost:6667 ; localhost:6668 ")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  public void rejectsAnEmptyEndpointWithAConfigurationError() {
    contextRunner
        .withPropertyValues("iotdb.session.node-urls=localhost:6667;")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("iotdb.session.node-urls contains an empty endpoint"));
  }

  @Test
  public void backsOffForUserPoolsAndClosesThemWithTheContext() {
    AtomicInteger closes = new AtomicInteger();
    ISessionPool tree = pool(ISessionPool.class, closes);
    ITableSessionPool table = pool(ITableSessionPool.class, closes);
    contextRunner
        .withBean(
            "customTreePool",
            ISessionPool.class,
            () -> tree,
            definition -> definition.setDestroyMethodName("close"))
        .withBean(
            "customTablePool",
            ITableSessionPool.class,
            () -> table,
            definition -> definition.setDestroyMethodName("close"))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ISessionPool.class)
                  .hasSingleBean(ITableSessionPool.class);
              assertThat(context.getBean(ISessionPool.class)).isSameAs(tree);
              assertThat(context.getBean(ITableSessionPool.class)).isSameAs(table);
            });
    assertThat(closes).hasValue(2);
  }

  @Test
  public void closesAutoConfiguredPoolsOnContextShutdown() {
    AtomicReference<ISessionPool> tree = new AtomicReference<>();
    AtomicReference<ITableSessionPool> table = new AtomicReference<>();
    contextRunner.run(
        context -> {
          tree.set(context.getBean(ISessionPool.class));
          table.set(context.getBean(ITableSessionPool.class));
        });
    assertThatThrownBy(() -> tree.get().executeQueryStatement("show databases"))
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> table.get().getSession()).hasMessageContaining("closed");
  }

  @Test
  public void customTreePoolDoesNotDisableTheTablePool() {
    ISessionPool tree = pool(ISessionPool.class, new AtomicInteger());
    contextRunner
        .withBean(ISessionPool.class, () -> tree)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ITableSessionPool.class);
              assertThat(context.getBean(ISessionPool.class)).isSameAs(tree);
            });
  }

  private static <T> T pool(Class<T> type, AtomicInteger closes) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getName().equals("close")) {
                closes.incrementAndGet();
              }
              return null;
            }));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class AutoConfigurationApplication {}
}
