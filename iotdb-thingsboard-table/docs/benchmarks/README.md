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

# IoTDB ThingsBoard Table — Benchmarks

This directory documents the performance test cases for the IoTDB Table Mode
ThingsBoard storage backend. It currently covers **TC-1 (ingestion
throughput)**, the write-throughput case for this backend.

## Two profiles

Each test case is defined with two profiles:

- **Smoke profile** — small, fast, reproducible on a laptop in well under ten
  minutes; single tenant; not wired into required CI, though it is CI-eligible
  wherever Docker is available. Its purpose is to exercise the real write path
  end to end and guard against gross regressions. **This is what is implemented
  today** (`IoTDBTableIngestionBenchmarkIT`).
- **Full profile** — a dedicated host, contributor-run, multi-backend
  comparison (Cassandra / PostgreSQL / TimescaleDB). It is *not* in CI and is
  **later-scope**; its report lives in [`report.md`](report.md) (placeholder
  until a fresh run is recorded).

## TC-1 — Ingestion throughput

Design intent: 1,000 devices writing simultaneously via 50 concurrent threads in
500-entry batches; measure records/sec and error rate. The calendar target is
**> 10,000 writes/sec**.

That **> 10K writes/sec figure is the full-profile headline on a dedicated
host.** A cold single-node container on a laptop or CI runner will not reach it,
so the smoke profile does not assert it.

### What the smoke benchmark does

`IoTDBTableIngestionBenchmarkIT` drives the **real** save path — the same code
ThingsBoard uses in production:

```
dao.save(tenant, entity, tsKvEntry, ttl)
  -> writer.enqueue(...)           bounded ArrayBlockingQueue (capacity 50,000)
  -> single flush worker           batches up to 500 rows, maxLingerMs 20
  -> Tablet insert                 multi-row table-session insert
  -> real IoTDB 2.0.8              apache/iotdb:2.0.8-standalone Testcontainer
```

It runs `SAVER_THREADS = 50` concurrent threads, each writing
`ROWS_PER_THREAD = 600` rows (30,000 rows total) with a distinct
`(entity, key, timestamp)` per write so nothing is deduplicated away. The
production save defaults are used unchanged (batchSize 500, queueCapacity
50,000, maxLingerMs 20, flushThreads 1, sessionPoolSize 8); only the retry
backoff is shortened so a transient cold-start blip does not stretch the
measured window. The total row count is kept below the queue capacity so the
run is free of back-pressure rejects without changing the real defaults.

### What it measures and asserts

Measured and logged:

- **records/sec** — `totalRows / wall-clock seconds`, timed from the first
  `save()` to all save futures completing.
- **error rate** — `failedFutures / totalRows`.
- **writer stats** — `dao.stats()`: `enqueued`, `flushed`, `flushFailures`,
  `retries`, `rejectsFull`, `rejectsShutdown`, `queueDepth`.
- **persisted-sample count** — a handful of rows are read back from IoTDB to
  prove real ingestion, not just future completion.

Asserted:

- error rate `== 0` and zero failed save futures;
- `flushFailures == 0`, `rejectsFull == 0`, `rejectsShutdown == 0`;
- `flushed == totalRows` (every distinct row reached IoTDB);
- the sampled rows are readable back from IoTDB;
- throughput `>=` a **conservative smoke floor of 1,000 rows/sec**.

#### Why the floor is 1,000 rows/sec, not 10,000

The smoke floor only guards against gross regressions and proves correctness on
a cold, shared, single-node container. It is intentionally an order of magnitude
below the full-profile headline so the test is not flaky on laptops or CI. Raise
it only alongside a measured full-profile report — never to chase the headline
number on CI.

### How to run it

The benchmark is named `*IT.java`, so the unit `mvn test` run never executes it;
it only test-compiles there. Integration tests in this module run via the
**`iotdb-table-it` profile** (maven-failsafe-plugin), which requires Docker.

The benchmark is tagged `@Tag("benchmark")` and `@Tag("integration")`. Use the
JUnit tag filter to select it. Run **only** the benchmark:

```bash
cd iotdb-thingsboard-table
mvn -ntp -Piotdb-table-it verify -Dgroups=benchmark
```

`-Dgroups=benchmark` runs only the `@Tag("benchmark")` test among the failsafe
`**/*IT.java` set, so the functional ITs are skipped and only the throughput
benchmark runs. Use `-Dgroups=benchmark`, **not**
`-Dtest=IoTDBTableIngestionBenchmarkIT`: a global `-Dtest=` overrides the
include/exclude filters of the surefire executions too, which can pull a Docker
IT into the unit `test` phase. The tag filter is applied on top of the file
patterns instead.

The benchmark also runs as part of the normal integration-test gate
(`mvn -ntp -Piotdb-table-it verify`): it is a deliberately cheap (~10 s, 30,000
rows) **throughput-regression guard** that asserts only a conservative floor of
1,000 records/sec, so it stays non-flaky on shared CI while still catching a
write-path performance regression. It needs no benchmark-specific pom wiring. If
you want a purely functional gate, exclude its tag:

```bash
cd iotdb-thingsboard-table
mvn -ntp -Piotdb-table-it verify -DexcludedGroups=benchmark
```

> **Gate note.** A bare `mvn -Piotdb-table-it verify` runs the benchmark as one
> of the `**/*IT.java` set (it is `@Tag("benchmark")`) — this is intended, as it
> guards the throughput floor. Use `-Dgroups=benchmark` to run *only* it and read
> the throughput number; use `-DexcludedGroups=benchmark` to skip it.

The measured records/sec and the full writer-stats report are emitted to the
test log at INFO and to stdout, so the figure is captured even when no SLF4J
binding is on the test classpath.

If Docker is unavailable the test is skipped
(`@Testcontainers(disabledWithoutDocker = true)`); it never fails the build for
lack of Docker.

### Smoke stack

The benchmark IT manages its own throwaway `apache/iotdb:2.0.8-standalone`
Testcontainer, so no external stack is required to run it. For a manual run
against a standalone node instead of the throwaway container, the module's
[`../../docker-compose.test.yml`](../../docker-compose.test.yml) brings up an
IoTDB service (among the full ThingsBoard test stack):

```bash
IOTDB_USERNAME=<iotdb-user> IOTDB_PASSWORD=<iotdb-password> \
  docker compose -f docker-compose.test.yml up -d iotdb

# reset to an empty store between runs
docker compose -f docker-compose.test.yml down -v
```

## Full-profile report

The full-profile multi-backend report is deferred; see
[`report.md`](report.md). The smoke-profile records/sec from a fresh run is also
filled in there by whoever runs the benchmark — the numbers are never committed
ahead of an actual run.
