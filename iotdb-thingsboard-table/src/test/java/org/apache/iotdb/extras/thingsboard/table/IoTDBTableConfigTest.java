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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.Errors;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoTDBTableConfigTest {

  @Test
  void defaults_haveExpectedValues() {
    IoTDBTableConfig config = new IoTDBTableConfig();

    assertEquals("127.0.0.1", config.getHost());
    assertEquals(6667, config.getPort());
    assertEquals(8, config.getSessionPoolSize());
    assertEquals("thingsboard", config.getDatabase());
    assertEquals(-1L, config.getDefaultTtlMs());
    assertEquals("root", config.getUsername());
    assertEquals("root", config.getPassword());
    assertEquals(5000, config.getConnectionTimeoutMs());
    assertFalse(config.isEnableCompression());
    assertFalse(config.getTs().isExperimentalRawOnly());
    assertEquals(4, config.getTs().getRead().getThreads());
    assertEquals(500, config.getTs().getSave().getBatchSize());
    assertEquals(20L, config.getTs().getSave().getMaxLingerMs());
    assertEquals(50000, config.getTs().getSave().getQueueCapacity());
    assertEquals(5000L, config.getTs().getSave().getShutdownDrainTimeoutMs());
    assertEquals(1, config.getTs().getSave().getFlushThreads());
    assertEquals(3, config.getTs().getSave().getRetryMaxAttempts());
    assertEquals(50L, config.getTs().getSave().getRetryInitialBackoffMs());
    assertEquals(1000L, config.getTs().getSave().getRetryMaxBackoffMs());
    assertEquals(10000, config.getTs().getRead().getQueueCapacity());
  }

  @Test
  void binding_fromProperties_overridesDefaults() {
    MapConfigurationPropertySource source =
        new MapConfigurationPropertySource(
            Map.ofEntries(
                Map.entry("iotdb.host", "10.0.0.5"),
                Map.entry("iotdb.port", "6668"),
                Map.entry("iotdb.session-pool-size", "16"),
                Map.entry("iotdb.database", "tenant_a"),
                Map.entry("iotdb.default-ttl-ms", "86400000"),
                Map.entry("iotdb.username", "iot_user"),
                Map.entry("iotdb.password", "iot_pass"),
                Map.entry("iotdb.connection-timeout-ms", "9000"),
                Map.entry("iotdb.enable-compression", "true"),
                Map.entry("iotdb.ts.experimental-raw-only", "true"),
                Map.entry("iotdb.ts.read.threads", "6"),
                Map.entry("iotdb.ts.read.queue-capacity", "7000"),
                Map.entry("iotdb.ts.save.batch-size", "250"),
                Map.entry("iotdb.ts.save.max-linger-ms", "30"),
                Map.entry("iotdb.ts.save.queue-capacity", "2000"),
                Map.entry("iotdb.ts.save.shutdown-drain-timeout-ms", "3000"),
                Map.entry("iotdb.ts.save.flush-threads", "1"),
                Map.entry("iotdb.ts.save.retry-max-attempts", "4"),
                Map.entry("iotdb.ts.save.retry-initial-backoff-ms", "10"),
                Map.entry("iotdb.ts.save.retry-max-backoff-ms", "100")));

    IoTDBTableConfig config = new Binder(source).bind("iotdb", IoTDBTableConfig.class).get();

    assertEquals("10.0.0.5", config.getHost());
    assertEquals(6668, config.getPort());
    assertEquals(16, config.getSessionPoolSize());
    assertEquals("tenant_a", config.getDatabase());
    assertEquals(86400000L, config.getDefaultTtlMs());
    assertEquals("iot_user", config.getUsername());
    assertEquals("iot_pass", config.getPassword());
    assertEquals(9000, config.getConnectionTimeoutMs());
    assertEquals(true, config.isEnableCompression());
    assertTrue(config.getTs().isExperimentalRawOnly());
    assertEquals(6, config.getTs().getRead().getThreads());
    assertEquals(7000, config.getTs().getRead().getQueueCapacity());
    assertEquals(250, config.getTs().getSave().getBatchSize());
    assertEquals(30L, config.getTs().getSave().getMaxLingerMs());
    assertEquals(2000, config.getTs().getSave().getQueueCapacity());
    assertEquals(3000L, config.getTs().getSave().getShutdownDrainTimeoutMs());
    assertEquals(1, config.getTs().getSave().getFlushThreads());
    assertEquals(4, config.getTs().getSave().getRetryMaxAttempts());
    assertEquals(10L, config.getTs().getSave().getRetryInitialBackoffMs());
    assertEquals(100L, config.getTs().getSave().getRetryMaxBackoffMs());
  }

  @Test
  void binding_throughSpringRejectsValueThatViolatesAJakartaConstraint() {
    // Mentor finding #2: prove @Validated on IoTDBTableConfig actually rejects a bad bound value
    // through Spring's real binding/validation path (Binder + ValidationBindHandler), not via a
    // hand-built Validator. iotdb.ts.save.flush-threads=2 violates @Max(1). The jakarta 3.0.2
    // constraint (matching the ThingsBoard 4.3.x Spring Boot 3 / jakarta runtime host) is enforced
    // by hibernate-validator 8.0.2 and surfaced as a Spring BindValidationException, so an operator
    // who sets an unsupported value gets a fail-fast binding error.
    MapConfigurationPropertySource source =
        new MapConfigurationPropertySource(Map.of("iotdb.ts.save.flush-threads", "2"));
    Binder binder = new Binder(source);
    ValidationBindHandler handler = new ValidationBindHandler(new JakartaValidatorAdapter());

    // Binder wraps the validation failure as a BindException whose cause is the
    // BindValidationException carrying the rejected jakarta constraint.
    BindException failure =
        assertThrows(
            BindException.class,
            () -> binder.bind("iotdb", Bindable.of(IoTDBTableConfig.class), handler));
    BindValidationException validationFailure = bindValidationCause(failure);
    assertTrue(
        validationFailure.getValidationErrors().getAllErrors().stream()
            .anyMatch(error -> error.toString().contains("ts.save.flushThreads")),
        "binding error should name the constrained flush-threads property: " + validationFailure);
  }

  private static BindValidationException bindValidationCause(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindValidationException bindValidationException) {
        return bindValidationException;
      }
    }
    throw new AssertionError(
        "expected a BindValidationException in the cause chain but found none: " + failure);
  }

  @Test
  void binding_throughSpringAcceptsAValidValue() {
    // The same Binder + ValidationBindHandler path must NOT reject a value that satisfies the
    // constraints, so the rejection above is attributable to the constraint and not to the wiring.
    MapConfigurationPropertySource source =
        new MapConfigurationPropertySource(Map.of("iotdb.ts.save.flush-threads", "1"));
    Binder binder = new Binder(source);
    ValidationBindHandler handler = new ValidationBindHandler(new JakartaValidatorAdapter());

    IoTDBTableConfig config =
        binder.bind("iotdb", Bindable.of(IoTDBTableConfig.class), handler).get();

    assertEquals(1, config.getTs().getSave().getFlushThreads());
  }

  @Test
  void validation_rejectsInvalidJakartaConstraints() {
    IoTDBTableConfig config = new IoTDBTableConfig();
    config.setHost(" ");
    config.setPort(70000);
    config.setSessionPoolSize(0);
    config.getTs().getRead().setThreads(0);
    config.getTs().getRead().setQueueCapacity(0);
    config.getTs().getSave().setShutdownDrainTimeoutMs(0);

    Set<String> violationPaths =
        validate(config).stream()
            .map(v -> v.getPropertyPath().toString())
            .collect(Collectors.toSet());

    assertTrue(violationPaths.contains("host"));
    assertTrue(violationPaths.contains("port"));
    assertTrue(violationPaths.contains("sessionPoolSize"));
    assertTrue(violationPaths.contains("ts.read.threads"));
    assertTrue(violationPaths.contains("ts.read.queueCapacity"));
    assertTrue(violationPaths.contains("ts.save.shutdownDrainTimeoutMs"));
  }

  @Test
  void validation_rejectsMalformedDatabaseName() {
    // The database name is spliced into CREATE DATABASE / USE DDL, so a name with a semicolon, a
    // space or a leading digit must be rejected by the @Pattern (IoTDB identifier) constraint.
    for (String bad : new String[] {"tb;drop", "tb db", "1tb", "tb-1", ""}) {
      IoTDBTableConfig config = new IoTDBTableConfig();
      config.setDatabase(bad);
      Set<String> violationPaths =
          validate(config).stream()
              .map(v -> v.getPropertyPath().toString())
              .collect(Collectors.toSet());
      assertTrue(
          violationPaths.contains("database"),
          "database name '" + bad + "' must be rejected by validation");
    }
  }

  @Test
  void validation_acceptsValidDatabaseName() {
    for (String good : new String[] {"thingsboard", "tb_custom", "_tb", "TB1", "tenant_a"}) {
      IoTDBTableConfig config = new IoTDBTableConfig();
      config.setDatabase(good);
      Set<String> violationPaths =
          validate(config).stream()
              .map(v -> v.getPropertyPath().toString())
              .collect(Collectors.toSet());
      assertFalse(
          violationPaths.contains("database"), "database name '" + good + "' must pass validation");
    }
  }

  private Set<ConstraintViolation<IoTDBTableConfig>> validate(IoTDBTableConfig config) {
    try (ValidatorFactory factory =
        Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()) {
      Validator validator = factory.getValidator();
      return validator.validate(config);
    }
  }

  /**
   * Bridges the jakarta {@link Validator} (jakarta.validation-api 3.0.2 + hibernate-validator
   * 8.0.2) into Spring's {@link org.springframework.validation.Validator} SPI that {@link
   * ValidationBindHandler} drives. The Spring Boot 2.7 build/test parent ships a {@code
   * SpringValidatorAdapter} bound to {@code javax.validation}, which is absent here on purpose
   * (this module pins the jakarta namespace to match the ThingsBoard 4.3.x runtime host), so the
   * jakarta constraints are surfaced through Spring binding via this minimal adapter.
   */
  private static final class JakartaValidatorAdapter
      implements org.springframework.validation.Validator {
    @Override
    public boolean supports(Class<?> clazz) {
      return IoTDBTableConfig.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
      try (ValidatorFactory factory =
          Validation.byDefaultProvider()
              .configure()
              .messageInterpolator(new ParameterMessageInterpolator())
              .buildValidatorFactory()) {
        Validator validator = factory.getValidator();
        for (ConstraintViolation<Object> violation : validator.validate(target)) {
          String field = violation.getPropertyPath().toString();
          errors.rejectValue(
              field,
              violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
              violation.getMessage());
        }
      }
    }
  }
}
