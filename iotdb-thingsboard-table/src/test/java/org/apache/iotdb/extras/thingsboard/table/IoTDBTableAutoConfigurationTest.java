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

import org.apache.iotdb.isession.pool.ITableSessionPool;

import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-configuration tests that drive the module through Spring Boot's real auto-configuration
 * discovery path ({@link AutoConfigurations#of}, the mechanism a real ThingsBoard Spring Boot app
 * uses via {@code META-INF/spring/...AutoConfiguration.imports}), rather than a plain
 * {@code @Import}. This proves the META-INF auto-config registration actually activates the module
 * the way a deployed application would, and that explicitly selecting the backend fails fast when
 * the host already provides a conflicting {@link TimeseriesDao}.
 */
class IoTDBTableAutoConfigurationTest {
  private static final String SESSION_POOL_BEAN_NAME =
      IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME;

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(IoTDBTableConfiguration.class));

  @Test
  void autoConfigDiscovery_withSelector_createsPoolAndDao() {
    contextRunner
        .withPropertyValues(
            "database.ts.type=iotdb-table",
            "iotdb.ts.experimental-raw-only=true",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000",
            // Offline test with no real IoTDB: skip the startup bootstrap so afterPropertiesSet()
            // never opens a session.
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              assertTrue(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertTrue(context.getBean(SESSION_POOL_BEAN_NAME, ITableSessionPool.class) != null);
              assertTrue(context.containsBean("ioTDBTableTimeseriesDao"));
              assertTrue(context.getBean(IoTDBTableTimeseriesDao.class) != null);
            });
  }

  @Test
  void autoConfigDiscovery_withSelectorButNoExperimentalFlag_createsNoBeans() {
    contextRunner
        .withPropertyValues(
            "database.ts.type=iotdb-table",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertFalse(context.containsBeanDefinition("timeseriesWriter"));
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
              assertTrue(context.getBeansOfType(TimeseriesDao.class).isEmpty());
            });
  }

  @Test
  void autoConfigDiscovery_withoutSelector_createsNoBeans() {
    contextRunner.run(
        context -> {
          assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
          assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
          assertFalse(context.containsBeanDefinition("schemaBootstrap"));
        });
  }

  @Test
  void autoConfigDiscovery_withoutSelector_doesNotBindOrValidateIoTDBProperties() {
    contextRunner
        .withPropertyValues("iotdb.database=bad-name")
        .run(
            context -> {
              assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
            });
  }

  @Test
  void autoConfigDiscovery_withoutThingsBoardClasspath_createsNoBeans() {
    contextRunner
        .withClassLoader(new FilteredClassLoader("org.thingsboard"))
        .withPropertyValues(
            "database.ts.type=iotdb-table",
            "iotdb.ts.experimental-raw-only=true",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              // Proves the context boots cleanly (no NoClassDefFoundError) when ThingsBoard is
              // absent, in addition to creating none of the module beans.
              assertThat(context).hasNotFailed();
              assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertFalse(context.containsBeanDefinition("timeseriesWriter"));
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
            });
  }

  @Test
  void autoConfigDiscovery_withSelectorEnabledButNoThingsBoardClasspath_bootsCleanlyWithNoBeans() {
    // Mentor finding #6: even with the IoTDB selector + experimental flag ENABLED, the whole
    // auto-config chain must boot cleanly when ThingsBoard is hidden from the classpath -- the
    // @ConditionalOnClass(TimeseriesDao) guard must back the module off without a
    // NoClassDefFoundError, and none of the module beans may be created.
    contextRunner
        .withClassLoader(new FilteredClassLoader("org.thingsboard"))
        .withPropertyValues(
            "database.ts.type=iotdb-table",
            "iotdb.ts.experimental-raw-only=true",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // The TimeseriesDao class is genuinely hidden from this context's classloader.
              assertThrows(
                  ClassNotFoundException.class,
                  () ->
                      context
                          .getClassLoader()
                          .loadClass("org.thingsboard.server.dao.timeseries.TimeseriesDao"));
              assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertFalse(context.containsBeanDefinition("timeseriesWriter"));
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
            });
  }

  @Test
  void hostProvidedTimeseriesDao_failsFastWhenIoTDBBackendEnabled() {
    contextRunner
        .withUserConfiguration(HostTimeseriesDaoConfiguration.class)
        .withPropertyValues(
            "database.ts.type=iotdb-table",
            "iotdb.ts.experimental-raw-only=true",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              Throwable failure = context.getStartupFailure();
              assertTrue(failure != null, "context should fail on conflicting TimeseriesDao");
              assertTrue(
                  failureContains(
                      failure,
                      "database.ts.type=iotdb-table with iotdb.ts.experimental-raw-only=true, "
                          + "but a non-IoTDB TimeseriesDao bean 'hostTimeseriesDao' is present; "
                          + "remove it or unset the IoTDB selector"),
                  "startup failure should explain the conflicting TimeseriesDao bean: " + failure);
            });
  }

  @Test
  void hostProvidedTimeseriesDaoUsingModuleBeanName_failsFastWhenIoTDBBackendEnabled() {
    contextRunner
        .withUserConfiguration(HostNamedTimeseriesDaoConfiguration.class)
        .withPropertyValues(
            "database.ts.type=iotdb-table",
            "iotdb.ts.experimental-raw-only=true",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              Throwable failure = context.getStartupFailure();
              assertTrue(failure != null, "context should fail on conflicting TimeseriesDao");
              assertTrue(
                  failureContains(
                      failure,
                      "database.ts.type=iotdb-table with iotdb.ts.experimental-raw-only=true, "
                          + "but a non-IoTDB TimeseriesDao bean 'ioTDBTableTimeseriesDao' is "
                          + "present; remove it or unset the IoTDB selector"),
                  "startup failure should explain the conflicting TimeseriesDao bean: " + failure);
            });
  }

  private static boolean failureContains(Throwable failure, String expected) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null && message.contains(expected)) {
        return true;
      }
    }
    return false;
  }

  @Configuration
  static class HostTimeseriesDaoConfiguration {
    static final TimeseriesDao HOST_DAO = new NoopTimeseriesDao();

    @Bean
    TimeseriesDao hostTimeseriesDao() {
      return HOST_DAO;
    }
  }

  @Configuration
  static class HostNamedTimeseriesDaoConfiguration {
    @Bean(name = IoTDBTableConfiguration.IOTDB_TABLE_TIMESERIES_DAO_BEAN_NAME)
    TimeseriesDao ioTDBTableTimeseriesDao() {
      return HostTimeseriesDaoConfiguration.HOST_DAO;
    }
  }

  /** Minimal host-supplied {@link TimeseriesDao} used only to trigger the back-off conditional. */
  private static final class NoopTimeseriesDao implements TimeseriesDao {
    @Override
    public ListenableFuture<List<ReadTsKvQueryResult>> findAllAsync(
        TenantId tenantId, EntityId entityId, List<ReadTsKvQuery> queries) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Integer> save(
        TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, long ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Integer> savePartition(
        TenantId tenantId, EntityId entityId, long tsKvEntryTs, String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Void> remove(
        TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void cleanup(long systemTtl) {
      throw new UnsupportedOperationException();
    }
  }
}
