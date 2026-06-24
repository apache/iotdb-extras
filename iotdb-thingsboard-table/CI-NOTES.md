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

# CI Notes

The `iotdb-extras` parent reactor builds and tests this module through the named
`with-thingsboard` profile (the module compiles with Java 17 language features and
integrates ThingsBoard SPIs, so it is an explicit opt-in rather than part of the
default reactor). CI activates it on the JDK 17+ jobs by passing `-P with-thingsboard`
(see `.github/workflows/compile-check.yml`); the JDK 8/11 jobs omit the flag and skip
it. The module overrides NO shared reactor versions — iotdb-session, tsfile and guava
are inherited from the parent (2.0.5 / 2.1.1 / 32.1.2-jre), like the sibling IoTDB
connectors; the only deliberate override is jakarta.validation-api 3.0.2 (the
ThingsBoard 4.3.x Spring Boot 3 runtime namespace). tsfile resolves to the reactor's
single 2.1.1, so this module introduces no tsfile-convergence conflict. Note: running
`mvn -P enforce` still reports one pre-existing `dependencyConvergence` finding on
`org.apache.httpcomponents:httpcore` (4.4.12 vs 4.4.16), which comes transitively from
`iotdb-session 2.0.5 -> libthrift 0.14.1` and is shared by every iotdb-session consumer
in the reactor (the parent pom pins httpclient but not httpcore); it is not introduced
by this module and would be resolved at the parent level. This file is a developer
reference of the local checks for the `iotdb-thingsboard-table` module; it is not itself
a GitHub Actions workflow.

## Candidate Checks

- Compile from the standalone module directory:
  `mvn compile -DskipTests`
- Run unit tests:
  `mvn test`
- Validate the local stack file:
  `docker compose -f docker-compose.test.yml config`
- Run Docker-backed integration tests only when Docker is available:
  `mvn -Piotdb-table-it verify`
- Start the optional local stack only when required environment values are set:
  `TB_POSTGRES_USER=<postgres-user> TB_POSTGRES_PASSWORD=<postgres-password> IOTDB_USERNAME=<iotdb-user> IOTDB_PASSWORD=<iotdb-password> docker compose -f docker-compose.test.yml up -d`

## Notes

- Keep this file inside the module. Do not copy it to `.github/workflows`.
- Do not store passwords, tokens, or local hostnames in CI configuration.
- Keep the Docker image tags aligned with the versions exercised by this module's
  integration-test profile.
