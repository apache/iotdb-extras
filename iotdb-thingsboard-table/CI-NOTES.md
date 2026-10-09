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
(see `.github/workflows/compile-check.yml`). CI uses JDK 17 and 21. IoTDB client,
TsFile and Guava versions follow the reactor (2.0.11 / 2.4.0 / 32.1.2-jre).
Jakarta validation remains a module-local 3.0.2 override to match the ThingsBoard
Spring Boot 3 host. Run dependency convergence separately after dependency changes;
a compile result does not establish convergence or real-host binary compatibility.

This file documents local checks; it is not a GitHub Actions workflow. Container
tests default to `apache/iotdb:2.0.11-standalone`; `-Diotdb.test.image` can select
another released server for compatibility testing.

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
