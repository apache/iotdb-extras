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

# IoTDB MyBatis runtime support

Small runtime adapters for MyBatis and MyBatis-Plus with **JDK 17+ and IoTDB JDBC 2.0.11**. MyBatis is a provided dependency; the application selects its MyBatis/Boot version.

## Install and register

From the repository root:

```sh
mvn -pl mybatis-support -am install
```

The `with-mybatis` distribution profile (`mvn package -Pwith-mybatis`) also ships this jar next to the generator plugin in `apache-iotdb-<version>-mybatis-generator-plugin-bin.zip`.

Add this application dependency (it is separate from the build-time generator plugin):

```xml
<dependency>
    <groupId>org.apache.iotdb</groupId>
    <artifactId>mybatis-support</artifactId>
    <version>2.0.4-SNAPSHOT</version>
</dependency>
```

Register the query interceptor on the IoTDB MyBatis configuration:

```xml
<plugins>
    <plugin interceptor="org.apache.iotdb.mybatis.IoTDBQueryInterceptor"/>
</plugins>
```

For MyBatis-Plus, expose `new IoTDBQueryInterceptor()` as a Spring `@Bean`. The [example](../examples/mybatisplus-generator/src/main/java/org/apache/iotdb/config/IoTDBMybatisConfiguration.java) registers it. For applications with several databases, register it only on the IoTDB `SqlSessionFactory`.

In JDBC 2.0.11, a table-model prepared SELECT executed with `execute()` returns true without populating `getResultSet()`. MyBatis normally uses these two calls and can return an empty result. The interceptor routes prepared queries and cursors through **one** `executeQuery()` call and exposes that result to MyBatis. It preserves statement/result ownership and propagates errors; it does not retry or re-execute the SQL.

## Type mapping

| IoTDB type | Java type | Read/write mapping |
|---|---|---|
| TIME / TIMESTAMP | `Long` | Built-in `LongTypeHandler`, raw server-precision ticks |
| FLOAT | `Float` | Built-in `FloatTypeHandler` |
| DATE | `LocalDate` | `IoTDBLocalDateTypeHandler` |
| BLOB | `byte[]` | `IoTDBBlobTypeHandler` |

DATE uses JDBC `setDate/getDate` and checks `wasNull()`. JDBC 2.0.11 does not implement the typed `getObject(..., LocalDate.class)` path and can return a non-null date object for SQL NULL.

BLOB uses the supported `setBinaryStream(int, InputStream, int)` overload and `getBytes`. The 2.0.11 driver's `setBytes` treats bytes as text, and `setBlob` is unsupported. Arbitrary bytes, including zero and non-UTF-8 bytes, must survive unchanged.

Configure generator column overrides:

```xml
<columnOverride column="reading_date" javaType="java.time.LocalDate"
    typeHandler="org.apache.iotdb.mybatis.type.IoTDBLocalDateTypeHandler"/>
<columnOverride column="payload" javaType="byte[]"
    typeHandler="org.apache.iotdb.mybatis.type.IoTDBBlobTypeHandler"/>
```

The handler must appear in both parameter mappings and result mappings. In MyBatis-Plus use `@TableName(autoResultMap = true)` and `@TableField(typeHandler = ...)`; generated XML also includes explicit mappings. Package scanning is available through `mybatis-plus.type-handlers-package=org.apache.iotdb.mybatis.type`.

## Time precision and write results

A timestamp such as `1700000000123456789L` is interpreted in the server's configured precision. Use matching values for all TIME and TIMESTAMP columns:

| Server precision | Example raw value |
|---|---|
| ms | `1700000000123L` |
| us | `1700000000123456L` |
| ns | `1700000000123456789L` |

There is no automatic unit conversion. `java.util.Date` and the driver's `setTimestamp` path cannot preserve arbitrary microsecond/nanosecond ticks; do not substitute them for Long without a separately verified conversion policy.

JDBC 2.0.11 does not report reliable affected-row counts through `getUpdateCount()`; MyBatis write methods can return **-1 (unknown)** after successful execution. Do not interpret `result > 0` as the success criterion. Failures raise exceptions. A multi-row insert, several chunks, or a Spring transaction annotation does not provide transactional rollback.

## Tests

```sh
mvn -pl mybatis-support -am test
```

Unit tests cover byte preservation, dates/NULLs, raw ms/us/ns values, and single execution of prepared queries. The [MyBatis](../examples/mybatis-generator/README.md) and [MyBatis-Plus](../examples/mybatisplus-generator/README.md) examples provide opt-in real-server tests that generate, compile and execute mappers.
