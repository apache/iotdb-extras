<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Verifying the compile-only ThingsBoard surface

This module implements ThingsBoard storage SPIs (`TimeseriesDao`,
`TimeseriesLatestDao`, `AttributesDao`) but does **not** depend on ThingsBoard at
build time. ThingsBoard's `dao` and `common/data` artifacts are not published to
Maven Central:

```
g:org.thingsboard.common AND a:data  -> numFound 0
g:org.thingsboard        AND a:dao   -> numFound 0
```

so `src/provided/java` carries a compile-only surface of the ThingsBoard types
this module touches. Those sources are visible to `javac` only and are excluded
from the built jar (see the `maven-jar-plugin` `org/thingsboard/**` exclude), so
the real ThingsBoard classpath supplies the genuine types at runtime.

That arrangement keeps the module buildable for everyone, but it has one cost: a
hand-written surface can drift from the real interfaces, and nothing in the
default build would notice. **This document is how you check that it has not.**

The procedure below is deliberately *not* wired into the default build. Doing so
would require every contributor and every CI run to clone and build ThingsBoard
first, which is the situation the compile-only surface exists to avoid.

## What it verifies

Removing the compile-only surface and putting the real ThingsBoard artifacts on
the classpath means `javac` type-checks this module against the genuine types,
and the tests then exercise the DAO logic through them.

| Layer | Covered |
|---|---|
| the surface binds | every stub type and all three `implements` clauses |
| behaviour under mocks | unit tests |
| behaviour against a real server | container integration tests, real IoTDB |

## Procedure

Requires JDK 17, Maven, Docker (for the integration tests) and roughly 250 MB of
disk: the shallow clone below is about 140 MB and grows to about 240 MB once the
two modules are built. The installed artifacts add about 10 MB to your local
Maven repository.

**1. Build the ThingsBoard artifacts locally.** Use the version this module
targets; it is declared as `thingsboard.version` in `pom.xml`.

```bash
git clone --depth 1 --branch v4.3.1.2 https://github.com/thingsboard/thingsboard.git
cd thingsboard
mvn install -pl common/data,dao -am -DskipTests
```

This installs `org.thingsboard.common:data` and `org.thingsboard:dao` (plus the
modules they need) into your local repository. It does not require a full
ThingsBoard build.

**2. Point the module at them.** Apply this change locally — **do not commit
it**, because the artifacts are not resolvable from any configured repository and
committing it would break every build that has not run step 1.

In `iotdb-thingsboard-table/pom.xml`:

* remove the `src/provided/java` entry from `<compileSourceRoots>`, leaving only
  `src/main/java`
* add, at `provided` scope:

```xml
<dependency>
    <groupId>org.thingsboard.common</groupId>
    <artifactId>data</artifactId>
    <version>4.3.1.2</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.thingsboard</groupId>
    <artifactId>dao</artifactId>
    <version>4.3.1.2</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-lang3</artifactId>
    <version>3.18.0</version>
    <scope>provided</scope>
</dependency>
```

`commons-lang3` must match the version ThingsBoard itself pins (`3.18.0` for
4.3.1.2; see `commons-lang3.version` in ThingsBoard's root `pom.xml`). The real
`EntityType` uses `org.apache.commons.lang3.Strings`, which does not exist before
3.18.0 — with an older version every test in a suite that touches `EntityType`
fails at static initialisation with `NoClassDefFoundError`, which looks like a
drift failure and is not one.

**3. Build and test.**

```bash
mvn -P with-thingsboard -pl iotdb-thingsboard-table test                      # unit
mvn -P with-thingsboard -P iotdb-table-it -pl iotdb-thingsboard-table verify  # + container ITs
```

`verify` also runs `apache-rat`, so if you add any file while working through
this it needs the standard licence header or the build fails after the tests
have already passed.

## Confirming the surface was actually removed

A green build proves nothing on its own: if the compile-only sources are still on
the source path, everything compiles exactly as before. Check the compiler line.

```
with the compile-only surface:     Compiling 59 source files
with the real artifacts:           Compiling 19 source files
```

The difference is the size of the compile-only surface. If you still see the
larger number, the `<compileSourceRoots>` edit did not take effect and the run
tells you nothing about the real types.

`mvn -P with-thingsboard -pl iotdb-thingsboard-table dependency:list | grep thingsboard`
should also list the real artifacts at `provided` scope.

## Result on 2026-08-25, against ThingsBoard 4.3.1.2

```
Compiling 19 source files            (59 with the compile-only surface)
Tests run: 204, Failures: 0, Errors: 0      unit
Tests run:  58, Failures: 0, Errors: 0      container ITs, real IoTDB
BUILD SUCCESS
```

Integration tests by suite: `IoTDBTableLatestDaoIT` 19,
`IoTDBTableTimeseriesAggregationIT` 14, `IoTDBTableAttributesDaoIT` 12,
`IoTDBTableTimeseriesDaoIT` 10, `IoTDBTableTtlIT` 2,
`IoTDBTableIngestionBenchmarkIT` 1.

The context-test fixture marks its test-only `JpaAttributeDao` bean lazy. With
the genuine ThingsBoard class in place, eagerly constructing that bean would
also require ThingsBoard's host-provided `jpaExecutorService`, which this
isolated module test intentionally does not bootstrap. The tests exercise bean
definition selection and removal; the lazy marker prevents an unrelated host
dependency from changing that scope and does not affect production code. Those
context assertions therefore do not prove that the host DAO itself can be
constructed in this isolated runner; host-application compatibility requires a
separate live ThingsBoard deployment.

No drift: every type in the compile-only surface matched, and all three
`implements` clauses bound against the real interfaces.

This verification procedure does not launch a live ThingsBoard instance. It
establishes that the types bind and that the DAO behaves correctly against a
real IoTDB while using them; it does not establish that ThingsBoard as a whole
runs on this DAO.

## When ThingsBoard publishes its artifacts

If `org.thingsboard.common:data` and `org.thingsboard:dao` become resolvable from
a configured repository, the compile-only surface can be deleted and step 2 can
become the permanent configuration. Until then, this procedure is the way to
check it.
