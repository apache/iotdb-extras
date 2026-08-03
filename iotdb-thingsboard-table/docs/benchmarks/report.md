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

# TC-1 Ingestion-Throughput Benchmark Report

Methodology is documented in [`README.md`](README.md). This file records the
measured numbers. **No figure here is committed ahead of an actual run** — every
value below is filled in from a fresh local run, not copied from a previous one.

## Smoke profile (`IoTDBTableIngestionBenchmarkIT`)

Single-node `apache/iotdb:2.0.8-standalone` Testcontainer; 50 concurrent saver
threads; 600 rows/thread (30,000 rows total); production save defaults
(batchSize 500, queueCapacity 50,000, maxLingerMs 20, flushThreads 1,
sessionPoolSize 8). records/sec is timed from the first `save()` to all save
futures completing.

How to reproduce:

```bash
cd iotdb-thingsboard-table
mvn -ntp -Piotdb-table-it verify -Dgroups=benchmark
```

Results (fresh runs, 2026-08-03):

| Field | Value |
| --- | --- |
| Date | 2026-08-03 |
| Host | Apple Silicon laptop (macOS) via Docker Desktop / Testcontainers — a shared dev host, not a dedicated benchmark machine |
| IoTDB image | `apache/iotdb:2.0.8-standalone` (Testcontainers-managed) |
| Total rows | 30,000 |
| Saver threads | 50 |
| Batch size | 500 |
| Queue capacity | 50,000 |
| Elapsed (s) | 0.55 |
| **records/sec** | **54,292** |
| Error rate | 0.0000 |
| flushed / flushFailures / rejectsFull | 30,000 / 0 / 0 |
| retries / rejectsShutdown / queueDepth | 0 / 0 / 0 |

A second back-to-back run on the same host and configuration measured **61,936 rows/sec** in 0.48 s (error rate 0; flushed / flushFailures / rejectsFull = 30,000 / 0 / 0). Both runs are stable; the conservative **54,292** figure anchors the ≈ 5.4× / ≈ 54× ratios below.

> The smoke run asserts only a conservative floor of 1,000 rows/sec on a cold,
> shared, single-node container (so it stays non-flaky as a CI regression guard).
> The observed **54,292 rows/sec** is ≈5.4× the **> 10,000 writes/sec** design
> target and ≈54× the 1,000 rows/sec smoke floor — but treat it as a
> regression-guard / peak figure, **not** as a pass of the sustained full-profile
> target. That target is defined for 1,000 devices on a dedicated host (see the
> full profile below); this smoke run drives only 50 distinct devices (one per
> saver thread), and because `entity_id` is a TAG column the device cardinality
> materially changes the write workload. The 30,000 rows also fit entirely in the
> 50,000-row queue (so `rejectsFull=0`, no back-pressure) and are drained by a
> single flush worker. The sustained 1,000-device > 10K target therefore remains
> the deferred full profile, not something this smoke run validates.

## Full profile (deferred / later-scope)

> **Status: deferred / later-scope.**

This section will hold the **full-profile** TC-1 ingestion-throughput results:
1,000 devices, 50 concurrent threads, 500-entry batches, run on a dedicated
host, with the **> 10,000 writes/sec** target and a multi-backend comparison
(Cassandra / PostgreSQL / TimescaleDB).

The full profile is not in CI and is run by a contributor on dedicated
hardware. To be filled in:

- Hardware and IoTDB topology (single node vs. cluster).
- Dataset: device count, keys per device, batch size, total rows.
- Measured records/sec, error rate, and p50 / p99 batch flush latency.
- Per-backend comparison table.
- Tuning notes (session pool size, flush threads, queue capacity, linger).
