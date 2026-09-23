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

# IoTDB Spring Boot Starter

Auto-configures the native IoTDB tree and table session pools. This is a Session API integration; JDBC/MyBatis applications use `iotdb-jdbc` and a JDBC data source instead.

## Compatibility and build

- JDK 17 or newer; CI builds on JDK 17 and 21.
- Default build line: Spring Boot 3.5.1 / Spring Framework 6.2.8.
- Spring Boot 4.1.1 / Spring Framework 7.0.9 is validated in CI.
- IoTDB Java client 2.0.11, with TsFile 2.4.0.
- The Extras artifact version remains `2.0.4-SNAPSHOT`. It is independent of the IoTDB server/client version; do not assume a `2.0.11` starter artifact exists.

From the repository root, install the starter and its parent locally:

```sh
mvn -Pwith-springboot -pl iotdb-spring-boot-starter -am clean install
```

Use the locally built artifact (or the version of Extras you have actually published):

```xml
<dependency>
    <groupId>org.apache.iotdb</groupId>
    <artifactId>iotdb-spring-boot-starter</artifactId>
    <version>2.0.4-SNAPSHOT</version>
</dependency>
```

The configuration processor is optional and is not a runtime dependency of consuming applications.

The same starter artifact supports both Spring Boot lines. Applications should use their own Spring
Boot parent or BOM so Spring dependencies resolve to the application's chosen Boot version. To run
the repository compatibility test for Boot 4:

```sh
mvn -Pwith-springboot,spring-boot4 -pl iotdb-spring-boot-starter -am clean test
```

## Configure

```properties
iotdb.session.node-urls=127.0.0.1:6667;127.0.0.1:6668
iotdb.session.username=${IOTDB_USERNAME:root}
iotdb.session.password=${IOTDB_PASSWORD:root}
iotdb.session.database=wind
iotdb.session.max-size=10
iotdb.session.connection-timeout-in-ms=5000
iotdb.session.query-timeout-in-ms=60000
```

Create the `wind` database and your table separately before issuing queries. The starter does not create schema.

Defaults below are checked against IoTDB 2.0.11. Unless marked otherwise, a property applies to both pools.

| Property under `iotdb.session` | Type | Default | Purpose |
|---|---|---|---|
| `node-urls` | String | `127.0.0.1:6667` | Semicolon-separated RPC endpoints; surrounding whitespace is trimmed |
| `username` | String | `root` | Client username |
| `password` | String | `root` | Client password |
| `database` | String | unset | Default database for the table pool only |
| `max-size` | Integer | `5` | Maximum sessions in each pool |
| `fetch-size` | Integer | `5000` | Rows per query batch, from `SessionConfig.DEFAULT_FETCH_SIZE` |
| `connection-timeout-in-ms` | Integer | `0` | Connection timeout in milliseconds; `0` means no timeout |
| `query-timeout-in-ms` | Long | `60000` | Query timeout in milliseconds; negative uses the server default, `0` disables the timeout |
| `wait-to-get-session-timeout-in-ms` | Long | `60000` | Pool acquisition timeout in milliseconds |
| `max-retry-count` | Integer | `60` | Connection retry limit; `0` disables retries |
| `retry-interval-in-ms` | Long | `500` | Delay between connection retries in milliseconds |
| `enable-auto-fetch` | Boolean | `true` | Refresh available DataNode endpoints in the background |
| `enable-compression` | Boolean | `false` | Enable Thrift compact protocol; match the server configuration |
| `use-ssl` | Boolean | `false` | Enable TLS |
| `trust-store` | String | unset | Trust store path for TLS connections |
| `trust-store-pwd` | String | unset | Trust store password for TLS connections |
| `zone-id` | ZoneId | JVM default timezone | Session timezone, for example `Asia/Shanghai` or `UTC` |
| `thrift-default-buffer-size` | Integer | `1024` | Initial Thrift buffer size in bytes |
| `thrift-max-frame-size` | Integer | `67108864` | Maximum Thrift frame size in bytes (64 MiB) |
| `enable-redirection` | Boolean | `false` | Redirect writes to the relevant leader; the starter retains its historical default, while the driver defaults to `true` |
| `enable-records-auto-convert-tablet` | Boolean | `true` | Tree pool record-to-tablet conversion only |
| `sql-dialect` | String | `table` | Deprecated compatibility property; does not select or disable a pool |

The default `fetch-size` changes from `1024` to the IoTDB client default of `5000`. An explicitly
configured `iotdb.session.fetch-size` still overrides it. The Thrift buffer default remains `1024`
bytes; this is a separate setting.

`connection-timeout-in-ms` now defaults to `0` (no timeout). Earlier starters left it unset and
failed at startup with a `NullPointerException` unless the property was configured explicitly.

`enable-compression` maps to `enableThriftCompression` for the table builder and
`enableThriftRpcCompaction` for the tree builder. IoTDB RPC compression is a separate setting and
retains the driver's default (`true` in 2.0.11), even when `enable-compression=false`.

The starter does not expose the builders' `enableIoTDBRpcCompression`, `keyStore`, `keyStorePwd`,
`sslProtocol`, or tree protocol `version` options as configuration properties. Supply a custom pool
bean when those options are needed.

Kebab-case, legacy underscore and camelCase names (for example `fetch-size`, `fetch_size` and
`fetchSize`) all bind through Spring Boot relaxed binding. Use kebab-case in new configuration.
`sql_dialect` is retained for binding compatibility but does not select or disable a pool: inject
`ITableSessionPool` for tables or `ISessionPool` for trees.

## Query and release resources

```java
import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.springframework.stereotype.Service;

@Service
public class Measurements {
    private final ITableSessionPool pool;

    public Measurements(ITableSessionPool pool) {
        this.pool = pool;
    }

    public void printLatest() throws IoTDBConnectionException, StatementExecutionException {
        try (ITableSession session = pool.getSession();
             SessionDataSet rows = session.executeQueryStatement(
                 "SELECT * FROM power_data_set LIMIT 10")) {
            while (rows.hasNext()) {
                System.out.println(rows.next());
            }
        }
    }
}
```

Closing the borrowed session returns it to the pool, including on exceptions. Tree queries return a `SessionDataSetWrapper`, which must also be closed. Spring closes the pool beans when the application context shuts down; application code should not close a shared pool after each query.

The starter creates `tableSessionPool` and `treeSessionPool` only when the application has not supplied a bean of the corresponding interface. A custom pool for one model does not disable the other model's default pool.

This starter does not install a Spring transaction manager. IoTDB JDBC 2.0.11 `commit` and `rollback` do not provide database rollback semantics, and `@Transactional` cannot add those semantics to native Session calls.

## Validation

```sh
mvn -Pwith-springboot -pl iotdb-spring-boot-starter -am test
```

The tests cover default configuration, automatic discovery, binding all 22 parameters with
kebab-case/underscore/camelCase names, propagation into both pools, special timeout/retry values,
invalid endpoints, custom bean backoff and context lifecycle without requiring an IoTDB server.
They verify that both pools use the default fetch size and honor an explicit override. TLS settings
are checked at configuration level; these tests do not perform a TLS handshake or a live query.
See the [application example](../examples/iotdb-spring-boot-start/README.md) for a live query.
