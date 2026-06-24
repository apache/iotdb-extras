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

# IoTDB ThingsBoard Table

## Overview

`iotdb-thingsboard-table` is a ThingsBoard historical-telemetry DAO backend
built on Apache IoTDB Table Mode. It lets a ThingsBoard deployment store
and serve time-series telemetry through IoTDB's table-session API instead of the
default Cassandra/SQL backends. It compiles against the reactor's IoTDB 2.0.5
table-session client; its integration tests run the real write path against an
`apache/iotdb:2.0.8-standalone` server. The module targets ThingsBoard v4.3.1.2. Because
it compiles with Java 17 language features (records and others), the
`iotdb-extras` parent reactor builds and tests it only on JDK 17+ through the
explicit, named `with-thingsboard` opt-in profile (it is not JDK-auto-activated):
CI passes `-P with-thingsboard` on the JDK 17/21 jobs while the 8/11 jobs omit it
and skip the module, and a plain reactor build never pulls it in.

## ThingsBoard SPI surface (Strategy F)

The ThingsBoard DAO SPI and value types (`org.thingsboard.*`) are not published
to Maven Central, so they cannot be a normal compile dependency. Strategy F
treats them as a **compile-only source surface** under `src/provided/java`: just
enough of the ThingsBoard interfaces and value objects to compile against. The
maven-jar-plugin excludes `org/thingsboard/**` from the built jar, so these
compile-only types never ship and never shadow the real ThingsBoard classes. At
runtime the actual ThingsBoard classpath supplies them. This keeps the module
buildable in isolation while binding to the genuine ThingsBoard types on a real
deployment.

The compile-only surface under `src/provided/java` was manually verified against
ThingsBoard `v4.3.1.2` (commit `c37fb509`): the `TimeseriesDao` SPI methods the
DAO consumes and the value-object accessors it reads were checked against the
upstream sources. A fully-automated check against the upstream artifact is not
possible because ThingsBoard's `dao`/`common-data` modules are not published to
Maven Central (the reason for Strategy F). As a guard against silent drift,
`StrategyFContractTest` pins the exact `TimeseriesDao` SPI method signatures the
DAO depends on, so any accidental edit to the local surface fails the build.

## Scope

This initial module delivers an inert-by-default foundation: `IoTDBTableBaseDao`
(session-pool lifecycle, schema/table bootstrap) and the
`IoTDBTableTimeseriesDao` write path (`save`), raw read, and delete. To exercise
it, set both `database.ts.type=iotdb-table` and
`iotdb.ts.experimental-raw-only=true`. Aggregation, latest telemetry, and
attribute/label DAOs are outside the current scope.

> **This is an incremental / experimental backend.** Explicitly enabling it
> routes ThingsBoard historical telemetry through IoTDB Table Mode for **raw read
> + write + delete only**. **Time-bucketed aggregation is NOT implemented yet**;
> aggregation, latest telemetry, and attributes are outside the current scope.

## Known limitations

**Same-timestamp type change across separate flushes.** The writer collapses
duplicate `(tenant, entity, key, timestamp)` saves *within a single flush* so the
last write wins, but it does not yet defend against a same-`(tenant, entity, key,
timestamp)` save whose value *type* changes between two **separate** flushes (for
example a `LONG` written in one flush and a `STRING` written at the same
timestamp in a later flush). Because each typed value lands in its own column
(`long_v`, `str_v`, ...), that single point can end up with two non-null typed
columns.

This is a deliberate current-scope decision: the cleanup that would prevent it (a
delete-then-insert overwrite on every save) is outside the current scope. The
behavior is **fail-fast, not silent**: a raw read of that one poisoned point
throws an `IllegalStateException` (the single-typed-column invariant enforced in
`IoTDBTableBaseDao`) rather than returning a wrong value. Every other point is
unaffected. This documented behavior is pinned by an integration test
(`IoTDBTableTimeseriesDaoIT`).

## Configuration

The backend is bound from `iotdb.*` Spring properties (see `IoTDBTableConfig`).
Key activation and operational flags:

| Property | Default | Meaning |
| --- | --- | --- |
| `database.ts.type` | _(unset)_ | Set to `iotdb-table` as the ThingsBoard historical-timeseries backend selector. |
| `iotdb.ts.experimental-raw-only` | `false` | Explicit opt-in for this initial raw-only backend. Must be `true` together with `database.ts.type=iotdb-table`; write, raw read, and delete are implemented, while time-bucketed aggregation is outside the current scope. |
| `iotdb.host` / `iotdb.port` | `127.0.0.1` / `6667` | IoTDB node address. |
| `iotdb.username` / `iotdb.password` | `root` / `root` | IoTDB credentials. |
| `iotdb.database` | `thingsboard` | Target IoTDB database. |
| `iotdb.session-pool-size` | `8` | Table session pool size. |
| `iotdb.schema.bootstrap` | `true` | When `true`, the module runs an idempotent startup bootstrap that reads `schema-iotdb-table.sql` from the classpath and creates the `telemetry` / `entity_attributes` tables (and database) on a fresh IoTDB before the first write. Set to `false` if you manage the schema out-of-band. |

The module is a Spring Boot **auto-configuration** (`IoTDBTableConfiguration`).
Its deployment host is ThingsBoard 4.3.x, which runs on Spring Boot 3.5.x, so the
active registration is
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
(paired with the `@AutoConfiguration` annotation). The legacy
`META-INF/spring.factories` `EnableAutoConfiguration` entry is retained only so the
module still activates if it is ever consumed by a Spring Boot 2.7 host; on Boot 3.x
it is ignored. Either way it activates in a real ThingsBoard deployment without the
host application having to component-scan `org.apache.iotdb.extras`.

## Build

The module is an explicit, named opt-in profile (`with-thingsboard`), so it is built
only when that profile is activated (a plain reactor build never pulls it in). It
requires JDK 17+:

```bash
# from the iotdb-extras repository root
mvn -pl iotdb-thingsboard-table -am -P with-thingsboard clean test
```

It can also be built standalone from the module directory:

```bash
cd iotdb-thingsboard-table
mvn compile -DskipTests
```

## Test

Run the Java test scaffold from the module directory:

```bash
mvn test
```

Run the Docker-backed integration tests only when required:

```bash
mvn -Piotdb-table-it verify
```

Start the local integration stack with explicit environment values:

```bash
TB_POSTGRES_USER=<postgres-user> TB_POSTGRES_PASSWORD=<postgres-password> \
  IOTDB_USERNAME=<iotdb-user> IOTDB_PASSWORD=<iotdb-password> \
  docker compose -f docker-compose.test.yml up -d
```

Stop and remove the local stack:

```bash
docker compose -f docker-compose.test.yml down -v
```

## Status

Initial module status: `IoTDBTableBaseDao` plus the `IoTDBTableTimeseriesDao`
write, raw-read, and delete paths are implemented behind
`database.ts.type=iotdb-table` and `iotdb.ts.experimental-raw-only=true`.
Without both properties, the module is inert. Aggregation, latest telemetry, and
attributes are outside the current scope.
