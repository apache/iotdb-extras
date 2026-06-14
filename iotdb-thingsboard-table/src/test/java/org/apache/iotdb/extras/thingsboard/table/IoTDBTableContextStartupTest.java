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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IoTDBTableContextStartupTest {
  private static final String SESSION_POOL_BEAN_NAME =
      IoTDBTableConfiguration.IOTDB_TABLE_SESSION_POOL_BEAN_NAME;

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TableContextTestConfiguration.class);

  @Test
  void noActivation_contextStartsWithoutIoTDBBeans() {
    contextRunner.run(
        context -> {
          assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
          assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
          assertFalse(context.containsBeanDefinition("ioTDBTableLatestDao"));
          assertFalse(context.containsBeanDefinition("ioTDBTableLabelDao"));
        });
  }

  @Test
  void tsTypeActivationWithExperimentalFlag_createsPoolAndTimeseries() {
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
            // Disable the startup schema bootstrap: this is an offline context test with no real
            // IoTDB, so the bootstrap's afterPropertiesSet() must not try to open a session.
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              assertTrue(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertTrue(context.getBean(SESSION_POOL_BEAN_NAME, ITableSessionPool.class) != null);
              assertTrue(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
              assertTrue(context.getBean(IoTDBTableTimeseriesDao.class) != null);
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableLatestDao"));
              assertFalse(context.containsBeanDefinition("ioTDBTableLabelDao"));
            });
  }

  @Test
  void tsTypeActivationWithoutExperimentalFlag_createsNoIoTDBBeansOrTimeseriesDao() {
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

  // This module implements only the timeseries backend, so the latest-telemetry selector must NOT
  // spin up a session pool or run the schema-bootstrap DDL before the latest DAO ships.
  @Test
  void tsLatestSelectorAlone_doesNotActivatePoolOrBootstrap() {
    contextRunner
        .withPropertyValues(
            "database.ts_latest.type=iotdb-table",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000")
        .run(
            context -> {
              assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
            });
  }

  // Likewise the label selector must NOT activate the pool or schema bootstrap yet.
  @Test
  void labelsSelectorAlone_doesNotActivatePoolOrBootstrap() {
    contextRunner
        .withPropertyValues(
            "iotdb.labels.enabled=true",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.session-pool-size=8",
            "iotdb.connection-timeout-ms=5000")
        .run(
            context -> {
              assertFalse(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertFalse(context.containsBeanDefinition("schemaBootstrap"));
              assertFalse(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
            });
  }

  @Test
  void uppercaseSelector_stillActivatesPoolAndDao() {
    contextRunner
        .withPropertyValues(
            "database.ts.type=IOTDB-TABLE",
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
              assertTrue(context.containsBean(SESSION_POOL_BEAN_NAME));
              assertTrue(context.containsBeanDefinition("ioTDBTableTimeseriesDao"));
            });
  }

  @Test
  void hostForeignSessionPoolDoesNotSuppressOrFeedModulePool() {
    contextRunner
        .withUserConfiguration(HostSessionPoolConfiguration.class)
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
              ITableSessionPool modulePool =
                  context.getBean(SESSION_POOL_BEAN_NAME, ITableSessionPool.class);
              assertTrue(context.containsBean("hostSessionPool"));
              assertNotSame(HostSessionPoolConfiguration.HOST_POOL, modulePool);
              IoTDBTableTimeseriesDao dao = context.getBean(IoTDBTableTimeseriesDao.class);
              IoTDBTableTimeseriesWriter writer = context.getBean(IoTDBTableTimeseriesWriter.class);
              assertSame(modulePool, dao.tableSessionPool);
              assertSame(modulePool, fieldValue(writer, "tableSessionPool"));
            });
  }

  private static Object fieldValue(Object target, String fieldName) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @Configuration
  static class HostSessionPoolConfiguration {
    static final ITableSessionPool HOST_POOL = mock(ITableSessionPool.class);

    @Bean
    @Primary
    ITableSessionPool hostSessionPool() {
      return HOST_POOL;
    }
  }

  // IoTDBTableConfiguration registers the DAO bean itself via its @Bean methods, so importing the
  // auto-configuration is enough -- no extra @ComponentScan is needed here. This mirrors how a real
  // ThingsBoard deployment activates the module purely through the Spring Boot auto-configuration
  // import, without component-scanning org.apache.iotdb.extras.
  @Configuration
  @Import(IoTDBTableConfiguration.class)
  static class TableContextTestConfiguration {}
}
