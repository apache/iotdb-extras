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

# Spring Boot Session example

This application uses Spring Boot 3.5.1, JDK 17+ and IoTDB client 2.0.11 through the locally built Extras starter (`2.0.4-SNAPSHOT`).

Build from the repository root:

```sh
mvn -Pwith-springboot,with-examples -pl examples/iotdb-spring-boot-start -am clean install
```

Start IoTDB 2.0.11. In the table dialect, create the database/table used by `IoTDBService`:

```sql
CREATE DATABASE IF NOT EXISTS wind;
USE wind;
CREATE TABLE IF NOT EXISTS power_data_set (device STRING TAG, value DOUBLE FIELD);
INSERT INTO power_data_set(time, device, value) VALUES (1, 'demo', 42.0);
```

Edit `src/main/resources/application.properties` for your endpoints and credentials, then run `IoTDBSpringBootStartApplication` from the IDE or `mvn spring-boot:run` in this directory. The service exposes `queryTableSessionPool()` and `querySessionPool()` for invocation from application code; startup itself does not issue those queries.

Both methods close results and return borrowed sessions even on exceptions. The commented live-query test requires a server and is not part of the offline unit-test suite. See the [starter reference](../../iotdb-spring-boot-starter/README.md) for configuration defaults, custom pools and transaction limitations.
