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

# Changelog

## Unreleased

### Breaking

- **Minimum supported Grafana raised to 12.3.0.** Grafana 9.x, 10.x and 11.x are no
  longer supported. The `@grafana/*` packages are webpack externals resolved from the
  host at runtime, so a plugin built against a newer Grafana loads and then fails on an
  older one rather than failing to build; the declared floor is now the version CI
  actually boots and loads the plugin against. See the compatibility section of the
  plugin README.

### Changed

- Frontend build migrated from the archived `@grafana/toolkit` to
  `@grafana/create-plugin`. Node 22 and npm replace Node 16 and Yarn, in both the CI
  workflow and the Maven `frontend-maven-plugin` execution.

Older entries: this file previously pointed at `../RELEASE_NOTES.md`, which does not
exist in this repository.