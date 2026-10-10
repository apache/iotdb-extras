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

## Compatibility

- All Java modules require JDK 17 and build against IoTDB 2.0.11 (TsFile 2.4.0). The JDK 8 and 11 build profiles are removed. (#132)
- MyBatis: TIME and TIMESTAMP columns map to `Long`, FLOAT to `Float`, DATE to `LocalDate` and BLOB to `byte[]`. Regenerate mapper interfaces and XML together. (#132)
- Spring Boot starter: `fetch-size` defaults to 5000 (was 1024), `connection-timeout-in-ms` defaults to 0, and `sql-dialect` is deprecated. (#132)
- The Grafana plugin requires Grafana 12.3.0 or later. (#118)

## New Modules

- ThingsBoard: `iotdb-thingsboard-table` stores ThingsBoard 4.3.1.2 historical telemetry, latest telemetry and entity attributes in the IoTDB table model. Each path has its own selector and is off by default. It includes time-bucketed aggregation, user and migration guides, and an ingestion benchmark. (#110, #113, #115, #116, #117, #119, #120, #121, #124, #125, #127, #137)
- Metric scrape: `metric-scrape` scrapes Prometheus text endpoints and writes the metrics to the IoTDB table model, with Docker and Kubernetes examples. (#111)
- MyBatis: `mybatis-support` adds runtime adapters for the 2.0.11 JDBC driver. (#132)

## Grafana Plugin

- Table-model SQL queries through the native IoTDB Go client, with time macros and `$__interval` / `$__interval_ms`. (#114, #122)
- Database, table and column pickers and a starter query in the table-model editor. (#134)
- Range, Instant and Both query types, legend format, and "No data" on empty results. (#123, #129)
- Table-model template variables, object-form variable queries, and multi-value variables in tree-model path prefixes. (#109, #126, #128)
- The build moves from grafana-toolkit to create-plugin, the IoTDB Go client is upgraded to 2.0.10, and the README screenshots are fixed. (#118, #130, #131)

## Connectors and Examples

- Flink SQL connector: the lookup join no longer fails on a null value, and no longer returns a row for a timestamp with no data. (#135)
- Flink DataStream sink: accepts multiple node URLs. (#84)
- Spring Boot starter: `IoTDBSessionPool` becomes an auto-configuration whose pool beans close with the context. (#85, #132)
- Collector: `IoTDBPushSource` consumes record-format subscription messages through the 2.0.11 API. (#132)
- Zeppelin interpreter: the jar with dependencies is built by default.
- Kafka, RocketMQ and RabbitMQ examples for the table model, and updated MyBatis and MyBatis-Plus examples. (#82, #83, #93, #132)
- Helm: unnecessary ConfigNode settings are removed. (#89)

## Build

- IoTDB, TsFile and build plugin versions are updated, and the build publishes to develocity.apache.org. (#94, #132, #133, #136)

## Known Limitations

- On servers using `us` or `ns` time precision, a DELETE with a time predicate can report success without deleting the row on IoTDB 2.0.11. This is a server-side issue.
- The Spark 2.4 / Scala 2.11 modules compile, but need a runtime migration before deployment on JDK 17.
