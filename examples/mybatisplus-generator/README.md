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

# MyBatis-Plus example

An IoTDB **table-model** example using **JDK 17+, MyBatis-Plus 3.5.15 and JDBC 2.0.11**. The default build uses Spring Boot 3.5.1; `spring-boot4` selects Boot 4.1.1 and `mybatis-plus-spring-boot4-starter`. Each build uses one Boot BOM for its complete dependency set. Velocity 2.4.1 is declared explicitly for code generation.

## Prepare and run

Create database `database1`, then create both `table1` and `table2` with this schema (replace the table name for the second table):

```sql
CREATE DATABASE IF NOT EXISTS database1;
USE database1;
CREATE TABLE IF NOT EXISTS table1 (
    region STRING TAG, plant_id STRING TAG, device_id STRING TAG,
    model_id STRING ATTRIBUTE, maintenance STRING ATTRIBUTE,
    temperature FLOAT FIELD, humidity FLOAT FIELD, status BOOLEAN FIELD,
    arrival_time TIMESTAMP FIELD, reading_date DATE FIELD, payload BLOB FIELD
);
```

Add the DATE/BLOB columns separately when migrating an existing table; CREATE IF NOT EXISTS does not change it.

Configure `src/main/resources/application.yml`, then run `org.apache.iotdb.Main` from the IDE. Startup does not run code generation. Mapper XML resides under **src/main/resources/mappers**, so it is packaged in the JAR. `IoTDBMybatisConfiguration` registers the query interceptor and the restricted SQL injector.

Build/test from the repository root:

```sh
mvn -Pwith-examples,with-springboot -pl examples/mybatisplus-generator -am clean verify
mvn -Pwith-examples,with-springboot,spring-boot4 -pl examples/mybatisplus-generator -am clean verify
```

## Supported operations

TIME plus **all TAG columns** identifies a row. The example uses `IoTDBTableMapper<T>` and explicit XML/service APIs. It no longer relies on `@MppMultiId`, `BaseMapper.*ById` or `IService.update`: those APIs do not establish IoTDB composite-key or FIELD-update semantics.

| Service operation | Behavior |
|---|---|
| `insert(row)` | Requires time; inserts the supplied values |
| `selectByKey(key)` | TIME + region + plantId + deviceId; NULL TAGs use IS NULL |
| `deleteByKey(key)` | Same full key; see the high-precision server limitation below |
| `upsertFields(row)` | Requires time and at least one non-null FIELD; INSERTs only the supplied FIELDs under that key |
| `updateAttributes(row)` | Updates modelId and maintenance for the TAG-defined device, across all timestamps; null clears an attribute |
| `list()` | Ordered query capped at 1000 rows |

`upsertFields` leaves omitted/null FIELDs unchanged and ignores ATTRIBUTE properties. `updateAttributes` writes **both** attribute properties, including null; it does not use time. Always supply the intended device TAGs. Null TAG components identify the null-valued device, not a wildcard.

Use the services when you need their input validation. Generated mappers are lower-level SQL APIs; calls to them must supply a valid key and at least one FIELD for a field patch. Custom query wrappers should also include bounded time/device filters. The injector retains MyBatis-Plus's ordinary behavior for unrelated mapper types; in a multi-database app use separate SqlSessionFactory configurations.

TIME/TIMESTAMP use **Long raw ticks in server precision**, FLOAT uses Float, DATE uses LocalDate, and BLOB uses byte[]. `autoResultMap`, field annotations and XML preserve the [runtime handlers](../../mybatis-support/README.md). Applications migrating from Date timestamps must update their conversions and callers.

Write methods return the JDBC update count, which can be **-1 (unknown)** after success. Failures raise exceptions. IoTDB JDBC 2.0.11 does not implement rollback; `@Transactional` and multiple inserts are not an atomic transaction.

## Explicit code generation

Run `org.apache.iotdb.CodeGenerator` from the IDE with these optional JVM properties:

| Property | Default |
|---|---|
| `iotdb.url` | `jdbc:iotdb://127.0.0.1:6667/database1?sql_dialect=table` |
| `iotdb.database` | `database1` |
| `iotdb.username` / `iotdb.password` | root / root |
| `iotdb.output` | `target/generated-iotdb` |

Program arguments select tables, defaulting to `table1 table2`. The URL's selected database must match `iotdb.database`.

The generator reads actual column **TIME/TAG/ATTRIBUTE/FIELD** categories with DESC and renders custom Velocity templates. Java files go to `target/generated-iotdb/java`, XML to `target/generated-iotdb/resources/mappers`. It overwrites files in that output directory on reruns; review and copy the entity, mapper and XML together into source/resources. Service/controller generation is disabled so regeneration preserves the handwritten validation APIs.

Generated mappings retain all key columns, nullable TAGs, identifier quoting and DATE/BLOB handlers. FIELD patches remain INSERT; UPDATE is generated only for ATTRIBUTE columns. Tables without attributes expose an unsupported `updateAttributes` default method. Invalid or colliding Java property names fail generation and require an explicit naming adaptation.

## Tests

Offline tests start the Spring context without a database, inspect both tables' mapped SQL, check validation and compile generated Java templates.

Opt into live generation/compilation/CRUD:

```sh
# Repository root; use a test server/account with CREATE/DROP DATABASE permission.
mvn -Pwith-examples,with-springboot,iotdb-mybatis-it -pl examples/mybatisplus-generator -am verify \
  '-Diotdb.it.url=jdbc:iotdb://127.0.0.1:6667/?sql_dialect=table' \
  -Diotdb.it.precision=ms

# Add spring-boot4 to the profile list to exercise Boot 4 against the same server.
```

`iotdb.it.username/password` default to root/root. `iotdb.it.precision` accepts ms/us/ns and must match the server. The suite fails when the server is unavailable; it creates and cleans up only its unique test database. It covers colliding timestamps across TAGs, NULL TAGs, FIELD patches, device attributes, DATE/BLOB/NULL, real metadata generation and compiled mapper execution.

The **ms** suite passes on official IoTDB 2.0.11 with Boot 3 and Boot 4. On servers configured with **us/ns precision**, a DELETE with a time predicate can report success without deleting the matching row. This server routing issue also reproduces with raw JDBC and causes the strict key-deletion assertions to fail. Reads and writes preserve raw Long timestamp precision, but key deletion on us/ns servers requires a server-side fix. Do not remove the time predicate as a workaround: doing so can delete other rows for the same device.
