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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.thingsboard.server.dao.attributes.AttributesDao;
import org.thingsboard.server.dao.sql.attributes.JpaAttributeDao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Behavioural cover for {@code AttributesDaoConflictGuard}, the post-processor that makes the IoTDB
 * attributes selector usable on a stock ThingsBoard.
 *
 * <p>ThingsBoard registers {@code JpaAttributeDao} as an unconditional {@code @Component} and
 * exposes no attributes-backend switch of its own, so selecting IoTDB has to withdraw the competing
 * definition. Withdrawing is only safe while a replacement of the same type is registered:
 * otherwise the context ends up with zero {@code AttributesDao} beans, which is a startup outage
 * rather than a degraded mode.
 *
 * <p>This class exists because the guard previously had none. A change to its behaviour passed 190
 * green unit tests without a single failure, which is precisely the gap a guard of this kind must
 * not have — it decides whether the host application starts at all.
 *
 * <p>The guard never instantiates a bean; it reads definitions and types only.
 *
 * <p><b>Two different stand-ins, and the difference is the point.</b> The guard identifies the one
 * bean it may withdraw conjunctively: bean name {@code jpaAttributeDao} AND resolved type {@code
 * org.thingsboard.server.dao.sql.attributes.JpaAttributeDao}. So the host's DAO is represented by
 * the compile-only stub of that exact class ({@link JpaAttributeDao}, Strategy F, excluded from the
 * built jar), and a Mockito-derived {@link AttributesDao} now stands for something else entirely --
 * a third-party backend or a user's own bean, which the guard must refuse to touch. An earlier
 * revision used the Mockito class for ThingsBoard's DAO; under a name-only rule that was
 * indistinguishable, and the indistinguishability was the defect.
 *
 * <p>What these tests do NOT establish: that {@code
 * org.thingsboard.server.dao.sql.attributes.JpaAttributeDao} is the right string. ThingsBoard's dao
 * artifact is not on Maven Central, so that name comes from reading ThingsBoard's own source at
 * v4.3.1.2, not from anything asserted here.
 */
class AttributesDaoConflictGuardTest {

  private static final String IOTDB_DAO_BEAN = "ioTDBTableAttributesDao";
  private static final String HOST_DAO_BEAN = "jpaAttributeDao";

  private static final String PEER_DAO_BEAN = "someOtherIoTDBAttributesDao";
  private static final String THIRD_PARTY_DAO_BEAN = "auditingAttributesDao";

  /** ThingsBoard's own DAO: the compile-only stub carrying the real fully-qualified name. */
  private static final Class<?> HOST_DAO_TYPE = JpaAttributeDao.class;

  /**
   * An AttributesDao that is neither ours nor ThingsBoard's -- a third-party backend or a bean the
   * operator wrote. The guard has no standing to delete this and must fail loudly instead.
   */
  private static final Class<?> THIRD_PARTY_DAO_TYPE = mock(AttributesDao.class).getClass();

  private static BeanFactoryPostProcessor guard() {
    return IoTDBTableConfiguration.EnabledAttributesConfiguration.attributesDaoConflictGuard();
  }

  private static DefaultListableBeanFactory factory(boolean withOurs, boolean withHost) {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    if (withOurs) {
      beanFactory.registerBeanDefinition(
          IOTDB_DAO_BEAN, new RootBeanDefinition(IoTDBTableAttributesDao.class));
    }
    if (withHost) {
      beanFactory.registerBeanDefinition(HOST_DAO_BEAN, new RootBeanDefinition(HOST_DAO_TYPE));
    }
    return beanFactory;
  }

  /** T1 — the competing definition is withdrawn and ours is left strictly alone. */
  @Test
  void withdrawsTheHostDaoAndLeavesOursUntouched() {
    DefaultListableBeanFactory beanFactory = factory(true, true);

    guard().postProcessBeanFactory(beanFactory);

    assertFalse(
        beanFactory.containsBeanDefinition(HOST_DAO_BEAN), "competing definition withdrawn");
    assertTrue(beanFactory.containsBeanDefinition(IOTDB_DAO_BEAN), "our definition untouched");
    assertEquals(
        1,
        beanFactory.getBeanNamesForType(AttributesDao.class, true, false).length,
        "exactly one AttributesDao candidate remains");
  }

  /**
   * T3 — the zero-bean invariant, and the case that actually occurred in production this morning:
   * our DAO bean was skipped by a condition, so withdrawing ThingsBoard's would have left the
   * context with no AttributesDao at all. The guard must refuse BEFORE mutating anything.
   */
  @Test
  void refusesToWithdrawWhenOurReplacementDidNotRegister() {
    DefaultListableBeanFactory beanFactory = factory(false, true);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(
        thrown.getMessage().contains("did not register"),
        "states that our DAO did not register: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains(HOST_DAO_BEAN),
        "names the bean it declined to withdraw: " + thrown.getMessage());
    assertTrue(
        beanFactory.containsBeanDefinition(HOST_DAO_BEAN),
        "nothing withdrawn: the throw precedes every mutation");
  }

  /** With no competing bean at all the guard is a no-op, not a failure. */
  @Test
  void isANoOpWhenOnlyOurDaoIsRegistered() {
    DefaultListableBeanFactory beanFactory = factory(true, false);

    guard().postProcessBeanFactory(beanFactory);

    assertTrue(beanFactory.containsBeanDefinition(IOTDB_DAO_BEAN), "our definition survives");
    assertEquals(
        1,
        beanFactory.getBeanNamesForType(AttributesDao.class, true, false).length,
        "exactly one AttributesDao candidate remains");
  }

  /**
   * A definition whose class cannot be resolved is not treated as an {@code AttributesDao}
   * candidate at all, so the guard neither withdraws it nor fails.
   *
   * <p>This documents why the guard's own unresolvable-type branch is defensive rather than
   * reachable from here: {@code getBeanNamesForType} cannot match a definition whose class it
   * cannot load, so such a bean never enters the partitioning loop. The test asserts the mechanism,
   * not just the outcome — if a future Spring version starts listing unresolvable definitions, the
   * candidate-count assertion fails and this comment stops being true.
   */
  @Test
  void anUnresolvableDefinitionIsNotAnAttributesDaoCandidate() {
    DefaultListableBeanFactory beanFactory = factory(true, false);
    RootBeanDefinition unresolvable = new RootBeanDefinition();
    unresolvable.setBeanClassName("org.thingsboard.server.dao.attributes.NoSuchAttributeDao");
    beanFactory.registerBeanDefinition(HOST_DAO_BEAN, unresolvable);

    assertEquals(
        1,
        beanFactory.getBeanNamesForType(AttributesDao.class, true, false).length,
        "the unresolvable definition is not listed as an AttributesDao candidate");

    guard().postProcessBeanFactory(beanFactory);

    assertTrue(
        beanFactory.containsBeanDefinition(HOST_DAO_BEAN),
        "an unresolvable definition is left alone rather than withdrawn");
    assertTrue(beanFactory.containsBeanDefinition(IOTDB_DAO_BEAN), "our definition untouched");
  }

  /**
   * ThingsBoard's DAO supplied as a pre-built singleton rather than a bean definition: visible to
   * the type scan, but there is no definition to remove. The guard must detect that in its
   * pre-flight and refuse, leaving the singleton in place.
   *
   * <p>No subclass or mock is involved: this is a genuine {@code DefaultListableBeanFactory} with a
   * real {@code registerSingleton} call, which is how such a bean actually arrives.
   */
  @Test
  void throwsWhenTheHostDefinitionCannotBeWithdrawn() {
    DefaultListableBeanFactory beanFactory = factory(true, false);
    beanFactory.registerSingleton(HOST_DAO_BEAN, new JpaAttributeDao());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(
        thrown.getMessage().contains("cannot be withdrawn"),
        "message explains the definition could not be withdrawn: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains(HOST_DAO_BEAN),
        "message names the bean: " + thrown.getMessage());
    assertTrue(
        beanFactory.containsSingleton(HOST_DAO_BEAN),
        "the bean it could not withdraw is still there: the throw precedes every mutation");
    assertTrue(beanFactory.containsBeanDefinition(IOTDB_DAO_BEAN), "our definition untouched");
  }

  /**
   * ThingsBoard's own attributes DAO for the context tests.
   *
   * <p>The declared return type is the concrete class, not {@code AttributesDao}, and that is
   * load-bearing. The guard runs before any bean is created, so it reads the type a definition
   * DECLARES, never the runtime class of an instance. ThingsBoard registers this bean by component
   * scan, whose definition carries the concrete class; an interface-typed {@code @Bean} factory
   * method would resolve only to {@code AttributesDao} and the guard would classify it as
   * unrecognised and refuse to start -- correctly, since at that point nothing distinguishes it
   * from a third-party backend.
   *
   * <p>The fixture is lazy because these tests exercise bean-definition selection, not the host DAO
   * itself. Constructing the genuine ThingsBoard class would otherwise require host-only
   * collaborators such as {@code jpaExecutorService}, which this isolated module context does not
   * bootstrap.
   */
  @Configuration(proxyBeanMethods = false)
  static class HostAttributesDaoConfiguration {
    @Bean(name = HOST_DAO_BEAN)
    @Lazy
    JpaAttributeDao jpaAttributeDao() {
      return new JpaAttributeDao();
    }
  }

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IoTDBTableConfiguration.class))
        .withUserConfiguration(HostAttributesDaoConfiguration.class);
  }

  /**
   * With the selector unset the attributes path stays inert: the host's own DAO survives untouched
   * and none of ours exist. This pins that the guard cannot fire without the property that
   * justifies it.
   */
  @Test
  void withoutTheSelectorTheHostDaoSurvivesAndNoneOfOursExist() {
    runner()
        .withPropertyValues(
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              assertTrue(context.containsBean(HOST_DAO_BEAN), "host DAO untouched");
              assertFalse(
                  context.containsBean(IOTDB_DAO_BEAN), "our attributes DAO is not registered");
            });
  }

  /**
   * With the selector set, the host's DAO is gone and exactly one AttributesDao remains, ours. This
   * is also the regression test for the removed {@code @ConditionalOnMissingBean(type =
   * AttributesDao)}, which previously skipped our bean on every stock ThingsBoard and left the
   * context with no AttributesDao at all.
   */
  @Test
  void withTheSelectorOurDaoReplacesTheHostDao() {
    runner()
        .withPropertyValues(
            "database.attributes.type=iotdb-table",
            "iotdb.attributes.cluster_mode=disabled",
            "iotdb.host=localhost",
            "iotdb.port=6667",
            "iotdb.username=root",
            "iotdb.password=root",
            "iotdb.schema.bootstrap=false")
        .run(
            context -> {
              assertFalse(context.containsBean(HOST_DAO_BEAN), "host DAO withdrawn");
              assertEquals(
                  1,
                  context.getBeanNamesForType(AttributesDao.class).length,
                  "exactly one AttributesDao remains");
              assertTrue(context.containsBean(IOTDB_DAO_BEAN), "and it is ours");
            });
  }

  /**
   * The withdrawal must be visible in the log, because it is the only signal an operator gets that
   * a bean from their own application was removed. The assertion covers all four facts a reader
   * needs: which bean, its concrete class, and the property and value that caused it.
   */
  @Test
  void logsAWarnNamingTheBeanItsClassAndTheCausingProperty() {
    Logger configurationLogger = (Logger) LoggerFactory.getLogger(IoTDBTableConfiguration.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    configurationLogger.addAppender(appender);
    try {
      guard().postProcessBeanFactory(factory(true, true));

      ILoggingEvent event =
          appender.list.stream()
              .filter(e -> e.getFormattedMessage().contains("Removed ThingsBoard bean"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no withdrawal log line was emitted"));

      assertEquals(Level.WARN, event.getLevel(), "the withdrawal is logged at WARN");
      String message = event.getFormattedMessage();
      assertTrue(message.contains(HOST_DAO_BEAN), "names the bean: " + message);
      assertTrue(message.contains(HOST_DAO_TYPE.getName()), "names its class: " + message);
      assertTrue(message.contains("database.attributes.type"), "names the property: " + message);
      assertTrue(message.contains("iotdb-table"), "names the value: " + message);
    } finally {
      configurationLogger.detachAppender(appender);
    }
  }

  /**
   * The guard mutates bean definitions where its throw-only siblings do not, so it must run ahead
   * of anything that might resolve AttributesDao.
   */
  @Test
  void runsAtHighestPrecedence() {
    BeanFactoryPostProcessor postProcessor = guard();

    assertTrue(postProcessor instanceof PriorityOrdered, "guard is PriorityOrdered");
    assertEquals(
        Ordered.HIGHEST_PRECEDENCE,
        ((PriorityOrdered) postProcessor).getOrder(),
        "guard runs at highest precedence");
  }

  /**
   * The first of the two defects this revision fixes. A second bean assignable to our own DAO type,
   * registered under a different name, used to satisfy an "is one of ours present?" boolean: both
   * survived and injection was ambiguous.
   *
   * <p>Such a bean is a peer implementation somebody registered on purpose, not ThingsBoard's. The
   * guard has no standing to delete it, so it refuses and leaves the context exactly as it found
   * it.
   */
  @Test
  void anIoTDBPeerUnderAnotherNameIsRefusedAndBothSurvive() {
    DefaultListableBeanFactory beanFactory = factory(true, false);
    beanFactory.registerBeanDefinition(
        PEER_DAO_BEAN, new RootBeanDefinition(IoTDBTableAttributesDao.class));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(
        thrown.getMessage().contains(PEER_DAO_BEAN), "names the peer: " + thrown.getMessage());
    assertTrue(beanFactory.containsBeanDefinition(PEER_DAO_BEAN), "the peer survives");
    assertTrue(beanFactory.containsBeanDefinition(IOTDB_DAO_BEAN), "and so does ours");
    assertEquals(
        2,
        beanFactory.getBeanNamesForType(AttributesDao.class, true, false).length,
        "nothing was withdrawn");
  }

  /**
   * The second defect. A third-party AttributesDao alongside ThingsBoard's own: the removability
   * check used to sit inside the mutation loop, so one bean could be withdrawn before the refusal.
   *
   * <p>Every reason to stop is now evaluated first, so the host's DAO is still present after the
   * throw even though it was, on its own, perfectly removable.
   */
  @Test
  void aThirdPartyDaoStopsTheWithdrawalOfTheHostDaoToo() {
    DefaultListableBeanFactory beanFactory = factory(true, true);
    beanFactory.registerBeanDefinition(
        THIRD_PARTY_DAO_BEAN, new RootBeanDefinition(THIRD_PARTY_DAO_TYPE));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(
        thrown.getMessage().contains(THIRD_PARTY_DAO_BEAN),
        "names the bean it will not remove: " + thrown.getMessage());
    assertTrue(
        beanFactory.containsBeanDefinition(THIRD_PARTY_DAO_BEAN), "the third-party bean survives");
    assertTrue(
        beanFactory.containsBeanDefinition(HOST_DAO_BEAN),
        "and so does the host's, though it was removable on its own");
  }

  /** Right name, wrong type: the authorisation is for one specific class, not for a bean name. */
  @Test
  void aBeanUsingTheHostNameWithAnotherTypeIsRefused() {
    DefaultListableBeanFactory beanFactory = factory(true, false);
    beanFactory.registerBeanDefinition(HOST_DAO_BEAN, new RootBeanDefinition(THIRD_PARTY_DAO_TYPE));

    assertThrows(IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(beanFactory.containsBeanDefinition(HOST_DAO_BEAN), "left untouched");
  }

  /**
   * Right type, wrong name: an operator who registered ThingsBoard's class themselves, under their
   * own name, made a deliberate choice. Withdrawing it is not what the documented opt-in promises.
   */
  @Test
  void theHostTypeUnderAnotherBeanNameIsRefused() {
    DefaultListableBeanFactory beanFactory = factory(true, false);
    beanFactory.registerBeanDefinition(
        "customJpaAttributeDao", new RootBeanDefinition(HOST_DAO_TYPE));

    assertThrows(IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(beanFactory.containsBeanDefinition("customJpaAttributeDao"), "left untouched");
  }

  /**
   * Something else holding our bean name. Found by direct lookup rather than by filtering the type
   * scan, so it is caught even when the imposter does not implement AttributesDao at all.
   */
  @Test
  void aBeanHoldingOurNameWithTheWrongTypeIsRefused() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition(
        IOTDB_DAO_BEAN, new RootBeanDefinition(THIRD_PARTY_DAO_TYPE));
    beanFactory.registerBeanDefinition(HOST_DAO_BEAN, new RootBeanDefinition(HOST_DAO_TYPE));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> guard().postProcessBeanFactory(beanFactory));

    assertTrue(
        thrown.getMessage().contains("rather than an IoTDBTableAttributesDao"),
        "explains what holds the name: " + thrown.getMessage());
    assertTrue(beanFactory.containsBeanDefinition(HOST_DAO_BEAN), "nothing was withdrawn");
  }
}
