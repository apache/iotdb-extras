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

# IoTDB MyBatis Generator plugin

Generates MyBatis models, mapper interfaces and XML for the IoTDB **table model**. Features include `batchInsert`, Lombok models, serializable models, Swagger comments and configurable JDBC-to-Java type mapping.

## Prerequisites

Use JDK 17+, IoTDB/JDBC 2.0.11, and MyBatis Generator 1.4.2. The plugin artifact belongs to Extras (`2.0.4-SNAPSHOT` in this checkout); its version does not track the IoTDB driver version.

From the repository root:

```sh
mvn -pl mybatis-generator,mybatis-support -am clean install
```

`mvn package -Pwith-mybatis` additionally builds `distributions/target/apache-iotdb-<version>-mybatis-generator-plugin-bin.zip`, which bundles the generator plugin jar together with the `mybatis-support` runtime jar.

## Configure the generator

Both the plugin and JDBC driver must be in the generator's plugin classloader. No absolute `classPathEntry` or manually copied JDBC jar is needed:

```xml
<plugin>
    <groupId>org.mybatis.generator</groupId>
    <artifactId>mybatis-generator-maven-plugin</artifactId>
    <version>1.4.2</version>
    <dependencies>
        <dependency>
            <groupId>org.apache.iotdb</groupId>
            <artifactId>mybatis-generator-plugin</artifactId>
            <version>2.0.4-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.apache.iotdb</groupId>
            <artifactId>iotdb-jdbc</artifactId>
            <version>2.0.11</version>
        </dependency>
    </dependencies>
    <configuration>
        <configurationFile>src/main/resources/generatorConfig.xml</configurationFile>
        <verbose>true</verbose>
        <overwrite>true</overwrite>
    </configuration>
</plugin>
```

Use the [example configuration](../examples/mybatis-generator/src/main/resources/generatorConfig.xml) as a starting point. Set the JDBC URL to `jdbc:iotdb://127.0.0.1:6667/test?sql_dialect=table`, update credentials, output packages and table names, and create the target database/table before generation.

Run from the consuming project's directory:

```sh
mvn mybatis-generator:generate
```

Generation reads database metadata and can overwrite generated sources. Review the diff before incorporating output into application code.

## IoTDB-specific mapping

Applications also need [mybatis-support](../mybatis-support/README.md), including its query interceptor and DATE/BLOB handlers. The query interceptor is required for MyBatis's prepared query path with the 2.0.11 table driver.

```xml
<plugin type="org.apache.iotdb.mybatis.plugin.BatchInsertPlugin">
    <property name="batchSize" value="500"/>
</plugin>
<plugin type="org.mybatis.generator.plugins.VirtualPrimaryKeyPlugin"/>
<plugin type="org.apache.iotdb.mybatis.plugin.IoTDBKeyPlugin"/>
```

- `IoTDBJavaTypeResolver` maps TIMESTAMP to **Long**. Values use the server's configured ms/us/ns precision without conversion. Set `jdbcType.FLOAT=java.lang.Float`; do not map high-precision timestamps to `Date`.
- The logical row identity is **TIME plus every TAG**. Keep `virtualKeyColumns` synchronized with the real schema; `IoTDBKeyPlugin` emits `IS NULL` for nullable TAG components in generated SELECT/DELETE predicates. ATTRIBUTE and FIELD columns are not keys.
- Disable `enableUpdateByPrimaryKey` and `enableUpdateByExample`. IoTDB 2.0.11 UPDATE changes ATTRIBUTE columns only and rejects `time` in its predicate, so MBG's key-based UPDATE statements cannot run; `IoTDBKeyPlugin` drops them and reports a generator warning if they are left enabled. To change FIELD values, INSERT the same key and the desired fields; omitted/null fields do not erase existing values. ATTRIBUTE updates affect the device across timestamps.
- Add DATE/BLOB `columnOverride` entries from [runtime support](../mybatis-support/README.md), so the same handlers apply to inserts, batch parameters and result maps.
- Use `delimitIdentifiers` / `delimitAllColumns` for SQL identifiers requiring quotes. Batch SQL uses MBG's formatting helpers and preserves configured handlers and escaping.
- Lombok/Swagger plugins require the corresponding annotation dependencies in the consuming application. Lombok is applied to primary-key and BLOB model classes as well as base records.

The examples use `ignoreQualifiersAtRuntime=true`: generation reads the configured schema, while runtime SQL uses the database selected by the JDBC URL.

## Batch behavior and migration

Call `mapper.batchInsert(records)`. The generated default method validates the entire list before writing and splits it into at most **500 rows** per SQL statement. `batchSize` must be a positive integer; lower it for wide rows, large BLOBs or server request limits. This is a row limit, not a byte-size limit.

- Empty list: returns 0 and issues no SQL.
- Null list or null element: throws `IllegalArgumentException` before any chunk is sent.
- Identity, autoincrement, generated-always and legacy `incrementField` columns are excluded.
- Disabled inserts or a table with no insertable columns produce no batch method/statement; the latter reports a generation warning.
- Models with a separate BLOB subclass use the all-fields type.

`batchInsertRows(@Param("records") List<T>)` is the internal mapped statement. **Regenerate the mapper interface and XML together** when migrating from the previous mapped `batchInsert` method. Calling the internal helper directly bypasses validation and chunking.

The sample configurations enable `UnmergeableXmlMappersPlugin` so reruns replace generated XML even when comments are suppressed, instead of accumulating duplicate statements. Preserve handwritten SQL separately or review it before regeneration.

The driver reports affected-row count as -1 (unknown); the public batch method preserves -1 if any chunk has an unknown count, otherwise it sums known counts. SQL failures propagate as exceptions. Earlier chunks may already have been written when a later chunk fails: IoTDB JDBC 2.0.11 does not implement rollback, and MyBatis/Spring transaction annotations cannot make the batch atomic.

Bound generated `selectAll`/Example queries in application code with time/device predicates and a limit. The checked-in simple example caps `selectAll` at 1000; MBG's standard templates do not add this cap automatically.

See the [runnable MyBatis example](../examples/mybatis-generator/README.md) and [MyBatis-Plus example](../examples/mybatisplus-generator/README.md) for integration tests and known server limitations. This plugin integrates with MyBatis Generator; the MyBatis-Plus example uses its own generator.
