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
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import java.util.List;

/**
 * Spring Boot auto-configuration entry point for the IoTDB Table Mode backend.
 *
 * <p>The deployment host is ThingsBoard 4.3.x, which runs on Spring Boot 3.5.x, so the active
 * registration is {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} paired with the
 * {@code @AutoConfiguration} annotation below. The legacy {@code META-INF/spring.factories} {@code
 * EnableAutoConfiguration} entry is retained only so the module still activates if it is ever
 * consumed by a Spring Boot 2.7 host; on Boot 3.x that entry is ignored. Either way the module
 * activates in a real ThingsBoard deployment without the host application having to component-scan
 * {@code org.apache.iotdb.extras}.
 *
 * <p>The {@code @Bean} methods below explicitly register the session pool, the timeseries writer,
 * the schema bootstrap, and the {@code @Repository} {@link IoTDBTableTimeseriesDao}. Explicit bean
 * methods are used in preference to {@code @ComponentScan}, which Spring deliberately filters out
 * of auto-configuration classes (it would otherwise re-scan the host application's packages). Each
 * bean stays under the same selected-and-explicitly-enabled activation guard, so this foundation is
 * inert unless {@code database.ts.type=iotdb-table} and {@code iotdb.ts.experimental-raw-only=true}
 * are both set. This initial module delivers only the timeseries backend; the latest-telemetry and
 * label selectors return when those DAOs are implemented.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = IoTDBTableConfiguration.TIMESERIES_DAO_CLASS_NAME)
public class IoTDBTableConfiguration {
  static final String IOTDB_TABLE_SESSION_POOL_BEAN_NAME = "iotdbThingsboardTableSessionPool";
  static final String IOTDB_TABLE_TIMESERIES_DAO_BEAN_NAME = "ioTDBTableTimeseriesDao";
  static final String TIMESERIES_DAO_CLASS_NAME =
      "org.thingsboard.server.dao.timeseries.TimeseriesDao";

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = TIMESERIES_DAO_CLASS_NAME)
  @Conditional(IoTDBTableRawOnlyEnabledCondition.class)
  @EnableConfigurationProperties(IoTDBTableConfig.class)
  static class EnabledRawOnlyConfiguration {

    // This module implements only the timeseries backend. The latest-telemetry
    // (database.ts_latest.type) and label (iotdb.labels.enabled) selectors are intentionally NOT
    // included here: those DAOs do not exist yet, so they must not spin up a session pool or schema
    // bootstrap for a backend that has not shipped. Those conditions return when the corresponding
    // DAOs are implemented.
    @Bean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME, destroyMethod = "close")
    @ConditionalOnMissingBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    ITableSessionPool tableSessionPool(IoTDBTableConfig config) {
      String nodeUrl = config.getHost() + ":" + config.getPort();
      ITableSessionPool pool =
          new TableSessionPoolBuilder()
              .nodeUrls(List.of(nodeUrl))
              .user(config.getUsername())
              .password(config.getPassword())
              .database(config.getDatabase())
              .maxSize(config.getSessionPoolSize())
              .connectionTimeoutInMs(config.getConnectionTimeoutMs())
              .enableCompression(config.isEnableCompression())
              .build();
      log.info(
          "IoTDB Table Mode session pool initialized: nodeUrl={}, database={}, poolSize={}, compression={}, defaultTtlMs(storageAccountingOnly)={}",
          nodeUrl,
          config.getDatabase(),
          config.getSessionPoolSize(),
          config.isEnableCompression(),
          config.getDefaultTtlMs());
      return pool;
    }

    @Bean
    IoTDBTableTimeseriesWriter timeseriesWriter(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableConfig config) {
      return new IoTDBTableTimeseriesWriter(tableSessionPool, config);
    }

    /**
     * Fails startup before any IoTDB pool/bootstrap/writer singleton is created if the explicit
     * IoTDB backend selection conflicts with a host-provided TimeseriesDao.
     */
    @Bean
    static BeanFactoryPostProcessor timeseriesDaoConflictGuard() {
      return new TimeseriesDaoConflictGuard();
    }

    /**
     * Registers the historical-telemetry DAO. The bean name {@code ioTDBTableTimeseriesDao} matches
     * the default component-scan name, and the string-based missing-bean guard avoids loading
     * ThingsBoard classes while evaluating auto-configuration metadata.
     */
    @Bean
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    @ConditionalOnMissingBean(type = TIMESERIES_DAO_CLASS_NAME)
    IoTDBTableTimeseriesDao ioTDBTableTimeseriesDao(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableTimeseriesWriter timeseriesWriter,
        IoTDBTableConfig config) {
      return new IoTDBTableTimeseriesDao(tableSessionPool, timeseriesWriter, config);
    }

    /**
     * Idempotent startup schema bootstrap. Only registered when the IoTDB Table Mode backend is
     * selected and explicitly enabled (same activation guard as the pool/DAO), the session pool
     * bean is present, and {@code iotdb.schema.bootstrap} is not disabled (defaults to {@code
     * true}).
     */
    @Bean
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    @ConditionalOnProperty(
        name = "iotdb.schema.bootstrap",
        havingValue = "true",
        matchIfMissing = true)
    IoTDBTableSchemaBootstrap schemaBootstrap(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableConfig config) {
      return new IoTDBTableSchemaBootstrap(tableSessionPool, config);
    }
  }

  private static final class TimeseriesDaoConflictGuard implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {
      Class<?> timeseriesDaoType = resolveTimeseriesDaoClass(beanFactory);
      for (String beanName : beanFactory.getBeanNamesForType(timeseriesDaoType, true, false)) {
        if (!isIoTDBTimeseriesDaoBean(beanFactory, beanName)) {
          throw new IllegalStateException(
              "database.ts.type=iotdb-table with iotdb.ts.experimental-raw-only=true, but a "
                  + "non-IoTDB TimeseriesDao bean '"
                  + beanName
                  + "' is present; remove it or unset the IoTDB selector");
        }
      }
    }

    private static boolean isIoTDBTimeseriesDaoBean(
        ConfigurableListableBeanFactory beanFactory, String beanName) {
      Class<?> beanType = resolveBeanType(beanFactory, beanName);
      if (beanType == null) {
        throw new IllegalStateException(
            "database.ts.type=iotdb-table with iotdb.ts.experimental-raw-only=true, but "
                + "TimeseriesDao bean '"
                + beanName
                + "' has no resolvable type; expose a concrete IoTDBTableTimeseriesDao type or "
                + "remove the bean");
      }
      // beanType is guaranteed non-null here (the null case throws above).
      return IoTDBTableTimeseriesDao.class.isAssignableFrom(beanType);
    }

    private static Class<?> resolveBeanType(
        ConfigurableListableBeanFactory beanFactory, String beanName) {
      Class<?> beanType = beanFactory.getType(beanName, false);
      if (beanType != null || !beanFactory.containsBeanDefinition(beanName)) {
        return beanType;
      }
      BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
      ResolvableType resolvableType = beanDefinition.getResolvableType();
      return resolvableType == ResolvableType.NONE ? null : resolvableType.resolve();
    }

    private static Class<?> resolveTimeseriesDaoClass(ConfigurableListableBeanFactory beanFactory) {
      try {
        return ClassUtils.forName(TIMESERIES_DAO_CLASS_NAME, beanFactory.getBeanClassLoader());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(
            "IoTDB Table Mode backend was enabled but TimeseriesDao is not on the classpath", e);
      }
    }
  }
}
