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

import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strategy-F guard tests.
 *
 * <p>Strategy F compiles against a copy of the ThingsBoard SPI surface under {@code
 * src/provided/java} and excludes {@code org/thingsboard/**} from the built jar so the stub classes
 * never ship and never shadow the real ThingsBoard types at runtime. These tests prove both
 * invariants stay true and break the build if they drift:
 *
 * <ol>
 *   <li><b>Packaging:</b> the built jar (when present) contains no {@code org/thingsboard/**} (nor
 *       {@code org/apache/commons/**}); the module pom must also declare the jar exclude so the
 *       guard holds even during the unit ({@code test}) phase before the jar exists.
 *   <li><b>SPI drift:</b> the {@link TimeseriesDao} SPI methods the DAO depends on still exist with
 *       the exact parameter and return types this module consumes, so a silent change to the
 *       provided surface fails the build.
 * </ol>
 *
 * <p>The compile-only surface under {@code src/provided/java} was manually verified against
 * ThingsBoard {@code v4.3.1.2} (commit {@code c37fb509}). A fully-automated check against the
 * upstream artifact is not possible because ThingsBoard's {@code dao}/{@code common-data} modules
 * are not published to Maven Central (the reason for Strategy F), so {@code
 * timeseriesDaoSpiMethodsMatchExpectedSignatures} below pins the exact SPI signatures as explicit
 * expectations to catch any accidental drift in the local surface.
 */
class StrategyFContractTest {

  @Test
  void builtJarDoesNotShipThingsBoardOrCommonsClasses() throws IOException {
    Path target = Path.of("target");
    List<Path> jars = new ArrayList<>();
    if (Files.isDirectory(target)) {
      try (var stream = Files.newDirectoryStream(target, "*.jar")) {
        stream.forEach(jars::add);
      }
    }
    // During `mvn test` the jar is not built yet; the pom-exclude assertion below is the guard for
    // that phase. During `mvn package`/`verify` the jar exists and must be clean.
    for (Path jar : jars) {
      try (JarFile jarFile = new JarFile(jar.toFile())) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          String name = entries.nextElement().getName();
          assertFalse(
              name.startsWith("org/thingsboard/"),
              "built jar " + jar + " must not ship the compile-only ThingsBoard surface: " + name);
          assertFalse(
              name.startsWith("org/apache/commons/"),
              "built jar " + jar + " must not ship shaded Apache Commons classes: " + name);
        }
      }
    }
  }

  @Test
  void modulePomDeclaresJarExcludes() throws IOException {
    String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        pom.contains("<exclude>org/thingsboard/**</exclude>"),
        "module pom must keep the maven-jar-plugin exclude for org/thingsboard/** (Strategy F)");
    assertTrue(
        pom.contains("<exclude>org/apache/commons/**</exclude>"),
        "module pom must keep the maven-jar-plugin exclude for org/apache/commons/**");
  }

  @Test
  void timeseriesDaoSpiMethodsMatchExpectedSignatures() throws NoSuchMethodException {
    // The DAO implements TimeseriesDao; if the provided SPI surface drifts, these reflective
    // lookups throw NoSuchMethodException and fail the build. Each lookup pins the exact parameter
    // and return types this module consumes from the ThingsBoard SPI as an explicit expectation,
    // so an accidental edit to the local compile-only surface (src/provided) breaks the build.
    // Verified against ThingsBoard v4.3.1.2 (commit c37fb509).
    assertSpiMethod(
        "findAllAsync",
        ListenableFuture.class,
        new Class<?>[] {TenantId.class, EntityId.class, List.class});
    assertSpiMethod(
        "save",
        ListenableFuture.class,
        new Class<?>[] {TenantId.class, EntityId.class, TsKvEntry.class, long.class});
    assertSpiMethod(
        "savePartition",
        ListenableFuture.class,
        new Class<?>[] {TenantId.class, EntityId.class, long.class, String.class});
    assertSpiMethod(
        "remove",
        ListenableFuture.class,
        new Class<?>[] {TenantId.class, EntityId.class, DeleteTsKvQuery.class});
    assertSpiMethod("cleanup", void.class, new Class<?>[] {long.class});

    // The DAO is a genuine TimeseriesDao implementation.
    assertTrue(TimeseriesDao.class.isAssignableFrom(IoTDBTableTimeseriesDao.class));
  }

  /**
   * Asserts that {@link TimeseriesDao} declares a method with exactly the given name, return type
   * and ordered parameter types. {@code getMethod} already throws {@link NoSuchMethodException} if
   * no method matches the exact parameter types; the extra assertions on the return type and the
   * resolved parameter-type array make the pinned expectation explicit (and the failure message
   * actionable) when the surface drifts.
   */
  private static void assertSpiMethod(
      String name, Class<?> expectedReturn, Class<?>[] expectedParams)
      throws NoSuchMethodException {
    Method method = TimeseriesDao.class.getMethod(name, expectedParams);
    assertEquals(
        expectedReturn,
        method.getReturnType(),
        "TimeseriesDao." + name + " return type drifted from the pinned SPI expectation");
    assertArrayEquals(
        expectedParams,
        method.getParameterTypes(),
        "TimeseriesDao." + name + " parameter types drifted from the pinned SPI expectation");
  }

  @Test
  void readTsKvQueryResultSurfaceUsedByDaoExists() throws NoSuchMethodException {
    // The raw read path constructs ReadTsKvQueryResult(queryId, entries, lastTs) and reads back the
    // queryId/lastEntryTs; ReadTsKvQuery exposes the query shape the DAO reads. Lock those down.
    ReadTsKvQueryResult.class.getMethod("getQueryId");
    ReadTsKvQueryResult.class.getMethod("getLastEntryTs");
    ReadTsKvQueryResult.class.getMethod("getData");

    ReadTsKvQuery.class.getMethod("getKey");
    ReadTsKvQuery.class.getMethod("getStartTs");
    ReadTsKvQuery.class.getMethod("getEndTs");
    ReadTsKvQuery.class.getMethod("getLimit");
    ReadTsKvQuery.class.getMethod("getOrder");
    ReadTsKvQuery.class.getMethod("getInterval");
  }
}
