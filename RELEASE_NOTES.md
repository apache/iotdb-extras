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

# Apache IoTDB Extras 2.0.11

Changes since v2.0.3. Built against IoTDB 2.0.11 (TsFile 2.4.0, Thrift 0.23.0).

Release date: to be set when the release vote passes.

## Compatibility

- All Java modules require JDK 17 and build against IoTDB 2.0.11. The JDK 8 and 11 build profiles are removed. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- The Spring Boot starter also supports Spring Boot 4 (Spring Framework 7.0.9) through the `spring-boot4` profile, and CI runs the starter and the MyBatis-Plus example on it. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- MyBatis: TIME and TIMESTAMP columns map to `Long`, FLOAT to `Float`, DATE to `LocalDate` and BLOB to `byte[]`. Regenerate mapper interfaces and XML together. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- MyBatis: applications must register `IoTDBQueryInterceptor` from the new `mybatis-support` module. With the 2.0.11 JDBC driver, a table-model prepared SELECT run with `execute()` returns `true` without populating `getResultSet()`. See `mybatis-support/README.md`. The `with-mybatis` distribution profile ships `mybatis-support` next to the generator plugin. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- Spring Boot starter: `fetch-size` defaults to 5000 (was 1024), `connection-timeout-in-ms` defaults to 0, and `sql-dialect` is deprecated. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- The Grafana plugin requires Grafana 12.3.0 or later. ([#118](https://github.com/apache/iotdb-extras/pull/118))

## New Modules

- ThingsBoard: `iotdb-thingsboard-table` stores ThingsBoard 4.3.1.2 historical telemetry, latest telemetry and entity attributes in the IoTDB table model. Each path has its own selector and is off by default. It includes time-bucketed aggregation, user and migration guides, and an ingestion benchmark. ([#110](https://github.com/apache/iotdb-extras/pull/110), [#113](https://github.com/apache/iotdb-extras/pull/113), [#115](https://github.com/apache/iotdb-extras/pull/115), [#116](https://github.com/apache/iotdb-extras/pull/116), [#117](https://github.com/apache/iotdb-extras/pull/117), [#119](https://github.com/apache/iotdb-extras/pull/119), [#120](https://github.com/apache/iotdb-extras/pull/120), [#121](https://github.com/apache/iotdb-extras/pull/121), [#124](https://github.com/apache/iotdb-extras/pull/124), [#125](https://github.com/apache/iotdb-extras/pull/125), [#127](https://github.com/apache/iotdb-extras/pull/127), [#137](https://github.com/apache/iotdb-extras/pull/137))
- Metric scrape: `metric-scrape` scrapes Prometheus text endpoints and writes the metrics to the IoTDB table model, with Docker and Kubernetes examples. ([#111](https://github.com/apache/iotdb-extras/pull/111))
- MyBatis: `mybatis-support` adds runtime adapters for the 2.0.11 JDBC driver. ([#132](https://github.com/apache/iotdb-extras/pull/132))

## Grafana Plugin

- Table-model SQL queries through the native IoTDB Go client, with time macros and `$__interval` / `$__interval_ms`. ([#114](https://github.com/apache/iotdb-extras/pull/114), [#122](https://github.com/apache/iotdb-extras/pull/122))
- Database, table and column pickers and a starter query in the table-model editor. ([#134](https://github.com/apache/iotdb-extras/pull/134))
- Range, Instant and Both query types, legend format, and "No data" on empty results. ([#123](https://github.com/apache/iotdb-extras/pull/123), [#129](https://github.com/apache/iotdb-extras/pull/129))
- Table-model template variables, object-form variable queries, and multi-value variables in tree-model path prefixes. ([#109](https://github.com/apache/iotdb-extras/pull/109), [#126](https://github.com/apache/iotdb-extras/pull/126), [#128](https://github.com/apache/iotdb-extras/pull/128))
- The build moves from grafana-toolkit to create-plugin, the IoTDB Go client is upgraded to 2.0.10, and the README screenshots are fixed. ([#118](https://github.com/apache/iotdb-extras/pull/118), [#130](https://github.com/apache/iotdb-extras/pull/130), [#131](https://github.com/apache/iotdb-extras/pull/131))

## Connectors and Examples

- Flink SQL connector: the lookup join no longer fails on a null value, and no longer returns a row for a timestamp with no data. ([#135](https://github.com/apache/iotdb-extras/pull/135))
- Flink DataStream sink: accepts multiple node URLs. ([#84](https://github.com/apache/iotdb-extras/pull/84))
- Spring Boot starter: `IoTDBSessionPool` is an `@AutoConfiguration` whose pool beans close with the context. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- Collector: `IoTDBPushSource` consumes record-format subscription messages through the 2.0.11 API. ([#132](https://github.com/apache/iotdb-extras/pull/132))
- Zeppelin interpreter: the jar with dependencies is built by default. ([166d75d](https://github.com/apache/iotdb-extras/commit/166d75d9afa81edf0bcbfa8a56d1845292ba71b3), [827ccea](https://github.com/apache/iotdb-extras/commit/827cceac0d9f2cfe2ebb752959b8975577a2734d), pushed without a PR)
- Kafka, RocketMQ and RabbitMQ examples for the table model, and updated MyBatis-Plus and Spring Boot examples and dependencies. ([#83](https://github.com/apache/iotdb-extras/pull/83), [#85](https://github.com/apache/iotdb-extras/pull/85), [#93](https://github.com/apache/iotdb-extras/pull/93), [#132](https://github.com/apache/iotdb-extras/pull/132))
- Helm: unnecessary ConfigNode settings are removed. ([#89](https://github.com/apache/iotdb-extras/pull/89))

## Build

- IoTDB, TsFile and build plugin versions are updated, and the build publishes to develocity.apache.org. ([#94](https://github.com/apache/iotdb-extras/pull/94), [#132](https://github.com/apache/iotdb-extras/pull/132), [#133](https://github.com/apache/iotdb-extras/pull/133), [#136](https://github.com/apache/iotdb-extras/pull/136))
- Repository README in English and Chinese. ([#82](https://github.com/apache/iotdb-extras/pull/82))

## Known Limitations

- On servers using `us` or `ns` time precision, a DELETE with a time predicate can report success without deleting the row on IoTDB 2.0.11. This is a server-side issue that also reproduces with raw JDBC; see [examples/mybatis-generator/README.md](examples/mybatis-generator/README.md) and [examples/mybatisplus-generator/README.md](examples/mybatisplus-generator/README.md).
- The Spark 2.4 / Scala 2.11 modules compile, but need a runtime migration before deployment on JDK 17.
