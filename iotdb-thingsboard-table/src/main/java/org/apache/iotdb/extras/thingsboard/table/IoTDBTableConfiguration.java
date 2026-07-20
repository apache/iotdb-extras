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
  static final String IOTDB_TABLE_LATEST_DAO_BEAN_NAME = "ioTDBTableLatestDao";
  static final String IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME = "ioTDBTableAttributesDao";
  static final String TIMESERIES_DAO_CLASS_NAME =
      "org.thingsboard.server.dao.timeseries.TimeseriesDao";
  static final String TIMESERIES_LATEST_DAO_CLASS_NAME =
      "org.thingsboard.server.dao.timeseries.TimeseriesLatestDao";
  static final String ATTRIBUTES_DAO_CLASS_NAME =
      "org.thingsboard.server.dao.attributes.AttributesDao";

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = TIMESERIES_DAO_CLASS_NAME)
  @Conditional(IoTDBTableRawOnlyEnabledCondition.class)
  @EnableConfigurationProperties(IoTDBTableConfig.class)
  static class EnabledRawOnlyConfiguration {

    // The session pool, writer and schema bootstrap are owned by the timeseries backend
    // (database.ts.type=iotdb-table + iotdb.ts.experimental-raw-only). The derived-latest DAO
    // (database.ts_latest.type) is registered below and REUSES this pool; it adds the latest
    // selector on top of the timeseries selector (see IoTDBTableLatestEnabledCondition), so it can
    // never activate without the writer that populates the telemetry table it reads. The label
    // (iotdb.labels.enabled) selector returns when that DAO is implemented.
    @Bean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME, destroyMethod = "close")
    @ConditionalOnMissingBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    ITableSessionPool tableSessionPool(IoTDBTableConfig config) {
      return buildSessionPool(config);
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
     * Fails startup (only when the latest selector is on) if the IoTDB latest backend is enabled
     * but a conflicting non-IoTDB {@code TimeseriesLatestDao} is present, mirroring the fail-fast
     * behavior of {@link #timeseriesDaoConflictGuard()} for the historical DAO so the latest path
     * does not silently back off to a different backend while the timeseries path runs on IoTDB.
     */
    @Bean
    @ConditionalOnClass(name = TIMESERIES_LATEST_DAO_CLASS_NAME)
    @Conditional(IoTDBTableLatestEnabledCondition.class)
    static BeanFactoryPostProcessor timeseriesLatestDaoConflictGuard() {
      return new TimeseriesLatestDaoConflictGuard();
    }

    /**
     * Registers the derived-latest DAO. It is gated by {@link IoTDBTableLatestEnabledCondition}
     * (the timeseries selector plus {@code database.ts_latest.type=iotdb-table}) so it only
     * activates when the IoTDB writer that populates the telemetry table is also active, and reuses
     * the module-owned named session pool. A conflicting host {@code TimeseriesLatestDao} fails
     * startup fast via {@link #timeseriesLatestDaoConflictGuard()} rather than silently shadowing
     * this DAO; the string-based missing-bean guard keeps auto-config metadata evaluation from
     * loading ThingsBoard classes.
     */
    @Bean
    @ConditionalOnClass(name = TIMESERIES_LATEST_DAO_CLASS_NAME)
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    @Conditional(IoTDBTableLatestEnabledCondition.class)
    @ConditionalOnMissingBean(type = TIMESERIES_LATEST_DAO_CLASS_NAME)
    IoTDBTableLatestDao ioTDBTableLatestDao(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableConfig config) {
      return new IoTDBTableLatestDao(tableSessionPool, config);
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

    /**
     * Second idempotent startup schema bootstrap that creates the {@code telemetry_latest} overlay
     * table from {@code schema-iotdb-table-latest.sql}. It is registered ONLY when the
     * derived-latest DAO is active (same {@link IoTDBTableLatestEnabledCondition} guard, the {@code
     * TimeseriesLatestDao} class and the module pool are present) and {@code
     * iotdb.schema.bootstrap} is not disabled. It deliberately carries NO
     * {@code @ConditionalOnMissingBean} so it always runs alongside {@link #schemaBootstrap()} (a
     * distinct bean name); both resources are self-contained ({@code CREATE DATABASE IF NOT EXISTS}
     * + {@code USE} + {@code CREATE TABLE IF NOT EXISTS}), so the two bootstrap beans are
     * order-independent and idempotent. When the latest selector is off, the overlay table is never
     * created (latest path stays inert).
     */
    @Bean
    @ConditionalOnClass(name = TIMESERIES_LATEST_DAO_CLASS_NAME)
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    @Conditional(IoTDBTableLatestEnabledCondition.class)
    @ConditionalOnProperty(
        name = "iotdb.schema.bootstrap",
        havingValue = "true",
        matchIfMissing = true)
    IoTDBTableSchemaBootstrap latestSchemaBootstrap(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableConfig config) {
      return new IoTDBTableSchemaBootstrap(
          tableSessionPool, config, IoTDBTableSchemaBootstrap.LATEST_SCHEMA_RESOURCE);
    }
  }

  /**
   * Entity-attribute backend, activated INDEPENDENTLY by {@code
   * database.attributes.type=iotdb-table} (see {@link IoTDBTableAttributesEnabledCondition}). It is
   * a separate inner configuration from {@link EnabledRawOnlyConfiguration} because the attribute
   * DAO routes separately from the time-series DAOs: it must be able to activate on its own
   * (attributes selector set, ts selectors unset) and must stay inert when no attributes selector
   * is present. Because no shipped ThingsBoard release exposes {@code database.attributes.type},
   * the default Phase-1 deployment leaves it unset, this configuration is skipped, no session pool
   * or attribute bean is created, and attributes keep flowing to the host entity-DB {@code
   * AttributesDao} (inert by default).
   *
   * <p>The session pool / schema bootstrap beans here reuse the same bean name as {@link
   * EnabledRawOnlyConfiguration} and carry {@code @ConditionalOnMissingBean(name=...)}, so when
   * both the timeseries and attributes selectors are on exactly one shared pool/bootstrap is
   * created; when only the attributes selector is on this configuration brings them up on its own.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = ATTRIBUTES_DAO_CLASS_NAME)
  @Conditional(IoTDBTableAttributesEnabledCondition.class)
  @EnableConfigurationProperties(IoTDBTableConfig.class)
  static class EnabledAttributesConfiguration {

    @Bean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME, destroyMethod = "close")
    @ConditionalOnMissingBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    ITableSessionPool tableSessionPool(IoTDBTableConfig config) {
      return buildSessionPool(config);
    }

    /**
     * Fails startup before any IoTDB pool/bootstrap singleton is created if the explicit IoTDB
     * attribute backend selection conflicts with a host-provided {@code AttributesDao}, mirroring
     * {@code timeseriesDaoConflictGuard()} so the attribute path does not silently shadow a
     * different backend.
     */
    @Bean
    static BeanFactoryPostProcessor attributesDaoConflictGuard() {
      return new AttributesDaoConflictGuard();
    }

    /**
     * Registers the entity-attribute DAO. The bean name {@code ioTDBTableAttributesDao} matches the
     * default component-scan name, the string-based missing-bean guard avoids loading ThingsBoard
     * classes while evaluating auto-configuration metadata, and the {@code @Bean} destroy method
     * drains the DAO's IO executor on shutdown.
     */
    @Bean(name = IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME, destroyMethod = "destroy")
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    @ConditionalOnMissingBean(type = ATTRIBUTES_DAO_CLASS_NAME)
    IoTDBTableAttributesDao ioTDBTableAttributesDao(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableConfig config) {
      return new IoTDBTableAttributesDao(tableSessionPool, config);
    }

    /**
     * Idempotent startup schema bootstrap for the attribute path. Shares the bean name with the
     * timeseries configuration via {@code @ConditionalOnMissingBean}, so it only registers when the
     * timeseries path has not already registered it.
     */
    @Bean
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
    @ConditionalOnMissingBean(IoTDBTableSchemaBootstrap.class)
    @ConditionalOnProperty(
        name = "iotdb.schema.bootstrap",
        havingValue = "true",
        matchIfMissing = true)
    IoTDBTableSchemaBootstrap attributesSchemaBootstrap(
        @Qualifier(IOTDB_TABLE_SESSION_POOL_BEAN_NAME) ITableSessionPool tableSessionPool,
        IoTDBTableConfig config) {
      return new IoTDBTableSchemaBootstrap(tableSessionPool, config);
    }
  }

  private static ITableSessionPool buildSessionPool(IoTDBTableConfig config) {
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

  private static final class TimeseriesLatestDaoConflictGuard implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {
      Class<?> latestDaoType = resolveTimeseriesLatestDaoClass(beanFactory);
      for (String beanName : beanFactory.getBeanNamesForType(latestDaoType, true, false)) {
        if (!isIoTDBLatestDaoBean(beanFactory, beanName)) {
          throw new IllegalStateException(
              "database.ts_latest.type=iotdb-table with the IoTDB timeseries backend enabled, but a "
                  + "non-IoTDB TimeseriesLatestDao bean '"
                  + beanName
                  + "' is present; remove it or unset the IoTDB latest selector");
        }
      }
    }

    private static boolean isIoTDBLatestDaoBean(
        ConfigurableListableBeanFactory beanFactory, String beanName) {
      Class<?> beanType = resolveBeanType(beanFactory, beanName);
      if (beanType == null) {
        throw new IllegalStateException(
            "database.ts_latest.type=iotdb-table with the IoTDB timeseries backend enabled, but "
                + "TimeseriesLatestDao bean '"
                + beanName
                + "' has no resolvable type; expose a concrete IoTDBTableLatestDao type or "
                + "remove the bean");
      }
      // beanType is guaranteed non-null here (the null case throws above).
      return IoTDBTableLatestDao.class.isAssignableFrom(beanType);
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

    private static Class<?> resolveTimeseriesLatestDaoClass(
        ConfigurableListableBeanFactory beanFactory) {
      try {
        return ClassUtils.forName(
            TIMESERIES_LATEST_DAO_CLASS_NAME, beanFactory.getBeanClassLoader());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(
            "IoTDB Table Mode backend was enabled but TimeseriesLatestDao is not on the classpath",
            e);
      }
    }
  }

  private static final class AttributesDaoConflictGuard implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {
      Class<?> attributesDaoType = resolveAttributesDaoClass(beanFactory);
      for (String beanName : beanFactory.getBeanNamesForType(attributesDaoType, true, false)) {
        if (!isIoTDBAttributesDaoBean(beanFactory, beanName)) {
          throw new IllegalStateException(
              "database.attributes.type=iotdb-table, but a non-IoTDB AttributesDao bean '"
                  + beanName
                  + "' is present; remove it or unset the IoTDB attributes selector");
        }
      }
    }

    private static boolean isIoTDBAttributesDaoBean(
        ConfigurableListableBeanFactory beanFactory, String beanName) {
      Class<?> beanType = resolveBeanType(beanFactory, beanName);
      if (beanType == null) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table, but AttributesDao bean '"
                + beanName
                + "' has no resolvable type; expose a concrete IoTDBTableAttributesDao type or "
                + "remove the bean");
      }
      // beanType is guaranteed non-null here (the null case throws above).
      return IoTDBTableAttributesDao.class.isAssignableFrom(beanType);
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

    private static Class<?> resolveAttributesDaoClass(ConfigurableListableBeanFactory beanFactory) {
      try {
        return ClassUtils.forName(ATTRIBUTES_DAO_CLASS_NAME, beanFactory.getBeanClassLoader());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(
            "IoTDB Table Mode backend was enabled but AttributesDao is not on the classpath", e);
      }
    }
  }
}
