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
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
  // ThingsBoard's own attributes component. Verified at v4.3.1.2 (tag c37fb509):
  // dao/src/main/java/org/thingsboard/server/dao/sql/attributes/JpaAttributeDao.java:58-61 is a
  // bare @Component on this class, so Spring's default name is the uncapitalised simple name.
  static final String JPA_ATTRIBUTE_DAO_CLASS_NAME =
      "org.thingsboard.server.dao.sql.attributes.JpaAttributeDao";
  static final String JPA_ATTRIBUTE_DAO_BEAN_NAME = "jpaAttributeDao";

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
   * is present. Leaving the selector unset is the default posture: this configuration is skipped,
   * no session pool or attribute bean is created, and attributes keep flowing to the host entity-DB
   * {@code AttributesDao} (inert by default). {@code database.attributes.type} is a selector this
   * module supplies rather than one ThingsBoard offers -- see {@link #attributesDaoConflictGuard()}
   * for what setting it does to the host's own attributes bean.
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
     * Resolves the attributes-backend conflict before any IoTDB pool/bootstrap singleton is
     * created. Unlike its timeseries siblings this guard does not only fail: ThingsBoard switches
     * its timeseries DAOs by configuration but offers no equivalent for attributes, so when the
     * IoTDB attributes backend is selected the guard <em>withdraws</em> ThingsBoard's own {@code
     * jpaAttributeDao} bean definition and logs a WARN naming it.
     *
     * <p>Withdrawal is deliberately narrow. It applies to exactly one bean, matched on both the
     * bean name {@code jpaAttributeDao} and the resolved type {@code
     * org.thingsboard.server.dao.sql.attributes.JpaAttributeDao}. Any <em>other</em> competing
     * {@code AttributesDao} — a third-party backend, a decorator, a subclass of this module's own
     * DAO — fails startup untouched, because a bean the operator registered deliberately is not
     * ours to delete.
     *
     * <p><b>Scope of the guarantee.</b> Candidates are discovered from a single {@code
     * getBeanNamesForType(type, true, false)} snapshot, which does not initialise FactoryBeans and
     * does not consult a parent factory. What this guard promises is therefore bounded to the
     * definitions visible in this bean factory at the moment it runs: a definition registered by a
     * later post-processor, produced by an opaque {@code FactoryBean} whose {@code getObjectType()}
     * is null until initialisation, or inherited from an ancestor context is outside it.
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
    // NOTE: deliberately NOT @ConditionalOnMissingBean(type = ATTRIBUTES_DAO_CLASS_NAME).
    // At ThingsBoard v4.3.1.2 JpaAttributeDao is an unconditional @Component, and that condition is
    // evaluated while configuration classes are parsed -- strictly BEFORE
    // attributesDaoConflictGuard() runs. Keeping it meant this bean was skipped on that build, so
    // the selector could never take effect. The guard now resolves the conflict instead: it
    // withdraws ThingsBoard's own attributes bean, or refuses to start if it finds any other
    // competing AttributesDao. Within the definitions visible to the guard when it runs, that
    // leaves exactly one -- this one. It is not a guarantee about definitions the guard cannot
    // see; see attributesDaoConflictGuard()'s javadoc for that boundary.
    @Bean(name = IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME, destroyMethod = "destroy")
    @ConditionalOnBean(name = IOTDB_TABLE_SESSION_POOL_BEAN_NAME)
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

  private static final class AttributesDaoConflictGuard
      implements BeanFactoryPostProcessor, PriorityOrdered {

    // This guard MUTATES bean definitions; its throw-only siblings do not. PriorityOrdered with
    // HIGHEST_PRECEDENCE puts it ahead of other regular BeanFactoryPostProcessors, which is what
    // keeps the withdrawal ahead of anything that would resolve AttributesDao through one. It does
    // NOT order this guard against BeanDefinitionRegistryPostProcessors, which run as a separate
    // earlier phase -- a definition registered there is simply part of the snapshot this guard
    // reads, while one registered by a LATER post-processor is outside what it can see at all.
    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {
      // PHASE 0 -- the only check that depends on no candidate, so it is answered once. Testing
      // this per-candidate made the failure message depend on iteration order.
      if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table, but this bean factory is not a "
                + "BeanDefinitionRegistry, so ThingsBoard's competing attributes bean cannot be "
                + "withdrawn; unset the IoTDB attributes selector");
      }
      Class<?> attributesDaoType = resolveAttributesDaoClass(beanFactory);

      // PHASE 1 -- read-only. Nothing below this point mutates until every reason to stop has
      // been evaluated.
      //
      // (a) OUR bean is found by DIRECT NAME LOOKUP, not by filtering the type snapshot.
      //     Assignability is not identity: a user subclass of IoTDBTableAttributesDao is
      //     somebody else's bean that happens to extend ours. Looking the name up directly also
      //     catches a bean that took our name while implementing something else entirely --
      //     that bean never enters the AttributesDao snapshot at all.
      boolean ourDefinitionPresent =
          beanFactory.containsBeanDefinition(IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME);
      Class<?> ourType =
          ourDefinitionPresent
              ? resolveBeanType(beanFactory, IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME)
              : null;

      // (b) every OTHER visible AttributesDao candidate falls into one of two classes:
      //
      //   KNOWN_TARGET  ThingsBoard's own attributes component, matched CONJUNCTIVELY on the
      //                 default component name AND the exact resolved class name. This is the
      //                 single bean the explicit database.attributes.type=iotdb-table selector
      //                 asks this module to replace, and the only one the documentation
      //                 names. Verified at ThingsBoard v4.3.1.2 (tag c37fb509):
      //                 JpaAttributeDao is a bare @Component on that class, hence that
      //                 name.
      //   UNKNOWN       everything else -- a subclass, a decorator, a third-party backend, or a
      //                 right-name/wrong-type imposter. Deleting a bean an operator wired on
      //                 purpose is worse than the ambiguity it would prevent, so these keep the
      //                 original fail-fast semantics, and that advice is now actionable: the
      //                 bean belongs to the application, which can remove it.
      //
      // Discovery is getBeanNamesForType(type, true, false): a one-time snapshot that does not
      // initialise FactoryBeans and does not consult a parent factory. Definitions registered
      // after this post-processor, produced by an opaque FactoryBean whose getObjectType() is
      // null until initialisation, or inherited from an ancestor context are outside what this
      // guard can see -- and therefore outside what it promises.
      // At most ONE bean can ever be the known target: the match is conjunctive on a fixed bean
      // name, and bean names are unique within a factory. A collection here would imply a
      // generality that cannot occur -- the same objection that removed an unreachable
      // "more than one of ours" branch from an earlier draft.
      String knownTargetName = null;
      Class<?> knownTargetType = null;
      Map<String, Class<?>> unknown = new LinkedHashMap<>();
      List<String> unresolvable = new ArrayList<>();

      for (String beanName : beanFactory.getBeanNamesForType(attributesDaoType, true, false)) {
        if (IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME.equals(beanName)) {
          continue;
        }
        Class<?> beanType = resolveBeanType(beanFactory, beanName);
        if (beanType == null) {
          unresolvable.add(beanName);
        } else if (JPA_ATTRIBUTE_DAO_BEAN_NAME.equals(beanName)
            && JPA_ATTRIBUTE_DAO_CLASS_NAME.equals(beanType.getName())) {
          knownTargetName = beanName;
          knownTargetType = beanType;
        } else {
          unknown.put(beanName, beanType);
        }
      }

      // An earlier version checked each candidate's removability inside the mutation loop, so it
      // could withdraw candidate one and then throw on candidate two, leaving a half-mutated
      // context that the surrounding comment claimed was impossible.
      if (!unresolvable.isEmpty()) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table, but AttributesDao bean(s) "
                + unresolvable
                + " have no resolvable type and cannot be classified; expose a concrete type or "
                + "remove the bean(s). Nothing was withdrawn");
      }
      if (!ourDefinitionPresent) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table, but the IoTDB Table Mode attributes DAO bean '"
                + IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME
                + "' did not register (check the session pool bean and the with-thingsboard "
                + "build); NOT withdrawing competing AttributesDao bean(s) "
                + competing(knownTargetName, unknown.keySet()));
      }
      if (ourType == null || !IoTDBTableAttributesDao.class.isAssignableFrom(ourType)) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table, but bean '"
                + IOTDB_TABLE_ATTRIBUTES_DAO_BEAN_NAME
                + "' is "
                + (ourType == null ? "of no resolvable type" : "a " + ourType.getName())
                + " rather than an IoTDBTableAttributesDao; something else holds this module's "
                + "bean name. Nothing was withdrawn");
      }
      if (!unknown.isEmpty()) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table selects the IoTDB attributes backend, but "
                + "bean(s) "
                + unknown
                + " also implement AttributesDao. This module withdraws only ThingsBoard's own '"
                + JPA_ATTRIBUTE_DAO_BEAN_NAME
                + "' ("
                + JPA_ATTRIBUTE_DAO_CLASS_NAME
                + "); it will not remove a bean your application registered. Remove the "
                + "conflicting bean(s) or unset the selector. Nothing was withdrawn");
      }
      if (knownTargetName != null && !registry.containsBeanDefinition(knownTargetName)) {
        throw new IllegalStateException(
            "database.attributes.type=iotdb-table, but ThingsBoard's attributes bean '"
                + knownTargetName
                + "' has no bean definition in this registry (it was most likely supplied as a "
                + "pre-built singleton) and cannot be withdrawn; unset the IoTDB attributes "
                + "selector. Nothing was withdrawn");
      }

      // PHASE 2 -- the single mutation. The bean removed here has been established to be
      // ThingsBoard's own component and to have a removable definition, so the WARN's wording is
      // true by construction rather than by assumption.
      if (knownTargetName != null) {
        registry.removeBeanDefinition(knownTargetName);
        log.warn(
            "Removed ThingsBoard bean '{}' ({}) because {}={} selects the IoTDB attributes "
                + "backend; ThingsBoard provides no configuration switch for attributes, so the "
                + "conflicting bean is deregistered rather than left to conflict.",
            knownTargetName,
            knownTargetType.getName(),
            IoTDBTableAttributesEnabledCondition.SELECTOR_PROPERTY,
            IoTDBTableAttributesEnabledCondition.SELECTOR_VALUE);
      }
    }

    /** Names every bean that competes for the AttributesDao role, for a refusal message. */
    private static List<String> competing(String knownTargetName, Collection<String> unknown) {
      List<String> all = new ArrayList<>();
      if (knownTargetName != null) {
        all.add(knownTargetName);
      }
      all.addAll(unknown);
      return all;
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
