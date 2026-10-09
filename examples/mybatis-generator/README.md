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

# MyBatis Generator example

Requires **JDK 17+, IoTDB/JDBC 2.0.11 and MyBatis 3.5.19**. The build-time generator and runtime support artifacts use Extras version `2.0.4-SNAPSHOT`.

## Prepare the schema

Start IoTDB with the table dialect and create:

```sql
CREATE DATABASE IF NOT EXISTS test;
USE test;
CREATE TABLE IF NOT EXISTS mix (
    device_id STRING TAG,
    region STRING ATTRIBUTE, plant_id STRING ATTRIBUTE,
    model_id STRING ATTRIBUTE, maintenance STRING ATTRIBUTE,
    temperature FLOAT FIELD, humidity FLOAT FIELD, status BOOLEAN FIELD,
    arrival_time TIMESTAMP FIELD, reading_date DATE FIELD, payload BLOB FIELD
);
```

If `mix` already exists from an older example, add the missing `reading_date DATE FIELD` and `payload BLOB FIELD` columns with ALTER TABLE; CREATE IF NOT EXISTS does not update an existing schema.

From the repository root:

```sh
mvn -Pwith-examples -pl examples/mybatis-generator -am clean install
```

Set the endpoint, database and credentials in `src/main/resources/generatorConfig.xml` and `mybatis-config.xml`. The latter registers `IoTDBQueryInterceptor` for the 2.0.11 prepared-query behavior. XML is included in the packaged JAR by the example's POM.

Run `org.apache.iotdb.mybatis.Main` from the IDE. Its timestamp literals assume the default **ms** server precision; use matching raw Long values on us/ns servers.

## Generate and migrate

From this example directory:

```sh
mvn mybatis-generator:generate
mvn test
```

Generation may overwrite the model, interface and XML; review all three together. `UnmergeableXmlMappersPlugin` replaces generated XML on reruns to prevent duplicate statement IDs. The default runtime is `MyBatis3Simple`. For Example/criteria generation:

```sh
mvn mybatis-generator:generate -Dmybatis.generator.configurationFile=src/main/resources/generatorConfigByExample.xml
```

Both configurations specify TIME + `device_id` as the key, use `IoTDBKeyPlugin` for nullable TAG predicates, and disable generic UPDATE. `ignoreQualifiersAtRuntime=true` selects the runtime database from the JDBC URL.

The Java API now uses **Long** for TIME/TIMESTAMP, **Float** for FLOAT, **LocalDate** for DATE and **byte[]** for BLOB. Update callers that previously supplied Date/Double. DATE/BLOB handlers are emitted in both parameter and result mappings; the runtime `mybatis-support` dependency is required after generation.

`batchInsert(records)` validates before writing and splits at 500 rows. Empty input returns 0; null input/elements throw before SQL. `batchInsertRows` is an internal statement: regenerate the interface and XML together when upgrading. JDBC reports unknown affected-row counts as -1; a negative result alone is not a SQL failure.

FIELD changes use INSERT on the same key. Generic UPDATE is disabled because IoTDB UPDATE supports ATTRIBUTE only. ATTRIBUTE values belong to the device across timestamps. Inserts, chunks and transaction annotations provide no rollback guarantee.

The checked-in `selectAll` has LIMIT 1000. MBG's standard regenerated `selectAll`/Example queries do not automatically retain this cap; add time/device predicates and limits appropriate to your workload.

## Tests

Default tests are offline and validate the packaged XML, interceptor registration, key predicates and handlers. Plugin/runtime tests also compile generated batch methods and cover empty/null inputs, chunking, quoting and type handling.

Real-server tests are explicit and fail if the server is unavailable:

```sh
# From the repository root; the account must be able to create/drop a test database.
mvn -Pwith-examples,iotdb-mybatis-it -pl examples/mybatis-generator -am verify \
  '-Diotdb.it.url=jdbc:iotdb://127.0.0.1:6667/?sql_dialect=table' \
  -Diotdb.it.precision=ms
```

Optional properties: `iotdb.it.username` / `iotdb.it.password` (default root/root), `iotdb.it.precision` (ms/us/ns; must match the server). Tests create a unique database and drop only that database, generate from live metadata into a temporary directory, compile the result, and exercise multi-chunk inserts, composite keys, reserved names, DATE/BLOB/NULL and raw timestamps.

On official IoTDB 2.0.11 servers configured with **us/ns precision**, a DELETE with a time predicate can report success without deleting the matching row. This server routing issue also reproduces with raw JDBC. The strict deletion assertions expose it; the **ms** suite passes. Reads and writes preserve raw Long timestamp precision, but key deletion on us/ns servers requires a server-side fix. Do not remove the time predicate as a workaround: doing so can delete other rows for the same device.

See the [plugin reference](../../mybatis-generator/README.md) and [runtime adapters](../../mybatis-support/README.md).
