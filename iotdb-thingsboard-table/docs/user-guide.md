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

# IoTDB ThingsBoard Table — User Guide

This guide explains how to configure, activate, and operate the IoTDB Table Mode
storage backend for ThingsBoard. For the architecture overview, the
compile-only ThingsBoard SPI surface, build/test instructions, and the design
rationale, see the module [`README.md`](../README.md).

## 1. What the module is

`iotdb-thingsboard-table` is a storage backend that lets a ThingsBoard
deployment persist telemetry, latest values, and (optionally) entity attributes
in Apache IoTDB 2.x relational **Table Mode** instead of the default
Cassandra / SQL backends. It implements ThingsBoard's `TimeseriesDao`,
`TimeseriesLatestDao`, and `AttributesDao` SPIs and ships as a Spring Boot
auto-configuration, so it activates inside a real ThingsBoard deployment without
the host application having to component-scan the module's package.

The module is built against the reactor's IoTDB 2.0.5 table-session client and
its integration tests exercise the real write path against an
`apache/iotdb:2.0.8-standalone` server. It targets ThingsBoard `4.3.1.2` and
requires JDK 17+.

**It is default-inert.** None of the three DAOs activate unless the operator
sets the explicit selector properties below. With no selector set, the module
contributes no beans and ThingsBoard keeps using its configured backends.

## 2. Prerequisites

- A running Apache IoTDB 2.x node with relational Table Mode, reachable from the
  ThingsBoard host over the IoTDB client RPC port.
- The target IoTDB database (default `thingsboard`) and its tables. On a fresh
  node the module's idempotent startup bootstrap creates them for you (see
  [§5](#5-schema-and-bootstrap)); operators who manage the schema out-of-band
  can disable the bootstrap and pre-create the tables from the shipped SQL.
- IoTDB credentials with permission to create the database/tables (when the
  bootstrap is enabled) and to read/write the tables.

## 3. Activation

Each path is selected independently and is inert until its selector is present.
Selector values are matched case-insensitively (trimmed).

| Path | Required properties | Default state |
| --- | --- | --- |
| Historical timeseries (`TimeseriesDao`) | `database.ts.type=iotdb-table` **and** `iotdb.ts.experimental-raw-only=true` | inert |
| Latest telemetry (`TimeseriesLatestDao`) | all of `database.ts.type=iotdb-table`, `iotdb.ts.experimental-raw-only=true`, **and** `database.ts_latest.type=iotdb-table` | inert |
| Entity attributes (`AttributesDao`) | `database.attributes.type=iotdb-table` (independent of the timeseries selectors) | inert (opt-in / stretch) |

Notes:

- **Timeseries** requires both `database.ts.type=iotdb-table` and the explicit
  experimental opt-in `iotdb.ts.experimental-raw-only=true`; setting only one of
  them leaves the path inert.
- **Latest** additionally requires `database.ts_latest.type=iotdb-table`. The
  timeseries selectors are required as well because the latest value is derived
  from the `telemetry` table that only the IoTDB writer populates — the latest
  path can never activate without that writer.
- **Attributes** is an opt-in stretch feature. No shipped
  ThingsBoard release exposes a `database.attributes.type` selector yet (open
  question Q6 / ThingsBoard Discussion #15296), so a real deployment normally
  leaves it unset and attributes keep flowing to the host entity database. When
  it is activated, `iotdb.attributes.cluster-mode` must also be set or the DAO
  fails fast at startup (see [§6](#6-configuration-reference)).

When activated, the DAOs share a single module-owned IoTDB table session pool.

### Conflict guards

When a selector is on, the module fails startup fast if a conflicting non-IoTDB
DAO bean of the same SPI type is also present (for example, another
`TimeseriesDao` while `database.ts.type=iotdb-table`). This is deliberate: it
prevents the module from silently shadowing, or being shadowed by, a different
backend. Remove the conflicting backend or unset the IoTDB selector.

## 4. The three DAOs

### 4.1 Timeseries DAO (`TimeseriesDao`)

- **Write** (`save`): telemetry points are accepted onto a bounded in-memory
  queue and flushed by a single writer thread as multi-row `Tablet` inserts into
  the `telemetry` table. Writes are asynchronous and batched; the call returns a
  future. Duplicate `(tenant, entity, key, timestamp)` saves within one flush are
  collapsed so the last write wins.
- **Raw read** (`findAllAsync` with no aggregation, or an interval below 1):
  returns the stored points for the query window, ordered and limited as
  requested.
- **Aggregated read** (`findAllAsync` with a positive interval): time-bucketed
  aggregation matching ThingsBoard's contract, in two forms:
  - **Fixed-width millisecond buckets** anchored at the query start timestamp,
    using IoTDB's `date_bin` primitive (supports `AVG`, `SUM`, `COUNT`, `MIN`,
    `MAX`).
  - **Timezone-aware calendar buckets** for `WEEK`, `WEEK_ISO`, `MONTH`, and
    `QUARTER` intervals, where each calendar boundary is computed in the query's
    timezone and one bounded aggregate query is issued per bucket.
- **Delete** (`remove`): deletes points for an identity within the query's
  half-open time window.
- **Retention hooks**: `cleanup` is a no-op and `savePartition` does nothing,
  because IoTDB Table Mode keeps no per-partition bookkeeping and physical
  retention is a table-level property (see [§7](#7-retention--ttl)).

### 4.2 Latest telemetry DAO (`TimeseriesLatestDao`)

Active only when all three timeseries+latest selectors are set.

- **Read** (`findLatest`, `findLatestOpt`, `findAllLatest`): the latest value per
  `(tenant, entity, key)` is read from **both** the historical `telemetry` table
  (derived via `ORDER BY time DESC LIMIT 1` / `LAST_BY`, engine-accelerated by
  IoTDB's last cache) **and** the `telemetry_latest` overlay table, then merged
  by the maximum timestamp per key (the overlay wins an exact tie). The merge is
  not additive, so a key present in both stores is never double-counted.
- **Write** (`saveLatest`): writes the per-key overlay row as a delete-then-insert
  under a per-identity lock. It is **max-timestamp-wins**: a backdated
  `saveLatest` whose timestamp is not newer than the stored latest is skipped, so
  an out-of-order write never regresses the latest. The overlay is written on
  every (non-backdated) call so a latest-only write — one with no paired
  historical `save`, e.g. the EntityView telemetry-copy path — is not lost.
- **Delete** (`removeLatest`): snapshots the merged latest under the per-identity
  lock; when that latest falls inside the half-open `[startTs, endTs)` window it
  deletes the overlay row, and when `rewriteLatestIfDeleted` is set it resurrects
  the next-older historical value (from `telemetry`) back into the overlay.
- **Key discovery** (`findAllKeysByEntityIds` / `…Async`): returns the distinct
  telemetry keys for the entity set, unioned across `telemetry` and the
  `telemetry_latest` overlay, so a latest-only key is still discoverable.
  `findAllKeysByDeviceProfileId` returns the tenant-wide distinct keys for a null
  (all-profiles) device profile and an empty list for a specific profile (the
  tables carry no device-profile membership).

### 4.3 Entity attributes DAO (`AttributesDao`) — opt-in / stretch

Inert by default; activated only by `database.attributes.type=iotdb-table`.

- **Write** (`save`): each identity tuple
  `(tenant_id, entity_type, entity_id, attribute_scope, key)` holds exactly one
  current row. `save` is a tag-only `DELETE` (no time predicate) followed by an
  `INSERT` at `time = lastUpdateTs` with one typed value column set, both under a
  per-identity lock so concurrent same-identity writes converge to a single row.
- **Read** (`find`, `findAll`, and the by-keys / by-entity-ids variants):
  synchronous `SELECT`s over the `entity_attributes` table.
- **Delete** (`removeAll`, `removeAllWithVersions`): one future per key, each a
  tag-only `DELETE`. `removeAllByEntityId` is a best-effort select-then-delete
  under an entity-level write lock.

## 5. Schema and bootstrap

The module uses one table per path. All three share the same five typed value
columns — `bool_v BOOLEAN`, `long_v INT64`, `double_v DOUBLE`, `str_v STRING`,
`json_v TEXT` — of which **exactly one is non-null per row**.

| Table | Purpose | TAG columns (in order) | TTL |
| --- | --- | --- | --- |
| `telemetry` | historical telemetry | `entity_type`, `tenant_id`, `key`, `entity_id` | `DEFAULT` |
| `telemetry_latest` | per-key latest overlay | `entity_type`, `tenant_id`, `key`, `entity_id` | `INF` |
| `entity_attributes` | entity attributes | `attribute_scope`, `entity_type`, `tenant_id`, `key`, `entity_id` | `INF` |

The shipped DDL lives in
[`schema-iotdb-table.sql`](../src/main/resources/schema-iotdb-table.sql)
(`telemetry` + `entity_attributes`) and
[`schema-iotdb-table-latest.sql`](../src/main/resources/schema-iotdb-table-latest.sql)
(`telemetry_latest`).

**Bootstrap.** When `iotdb.schema.bootstrap` is `true` (the default), the module
runs an idempotent startup bootstrap that reads the schema SQL from the classpath
and creates the database and tables before the first write. Every statement uses
`CREATE … IF NOT EXISTS`, so re-runs are harmless. The base schema is created
whenever the timeseries or attributes path is active; the `telemetry_latest`
overlay is created by a second bootstrap only when the latest path is active.
Set `iotdb.schema.bootstrap=false` to manage the schema out-of-band.

## 6. Configuration reference

All module knobs are bound from the `iotdb.*` prefix; the three `database.*`
selectors and `iotdb.ts.experimental-raw-only` are the activation switches from
[§3](#3-activation). Defaults are shown.

### Connection

| Property | Default | Notes |
| --- | --- | --- |
| `iotdb.host` | `127.0.0.1` | IoTDB node host. |
| `iotdb.port` | `6667` | IoTDB client RPC port (1–65535). |
| `iotdb.database` | `thingsboard` | Target Table Mode database. Must be a valid IoTDB identifier (a letter or underscore, then letters/digits/underscores). |
| `iotdb.username` | `root` | Set a real credential in production. |
| `iotdb.password` | `root` | Set a real credential in production. |
| `iotdb.session-pool-size` | `8` | Table session pool size (1–1024). Keep it at least `iotdb.ts.read.threads + iotdb.ts.save.flush-threads`, or the module logs a warning that reads/flushes may wait for a session. |
| `iotdb.connection-timeout-ms` | `5000` | Connection timeout (minimum 100). |
| `iotdb.enable-compression` | `false` | RPC compression. |
| `iotdb.default-ttl-ms` | `-1` | ThingsBoard storage data-point accounting only; **not** an IoTDB physical-retention setting. `-1` disables the default. |
| `iotdb.schema.bootstrap` | `true` | Run the idempotent startup schema bootstrap. Set `false` to manage the schema yourself. |

### Write path (`iotdb.ts.save.*`)

| Property | Default | Notes |
| --- | --- | --- |
| `batch-size` | `500` | Rows per `Tablet` insert. |
| `max-linger-ms` | `20` | Maximum time a partial batch waits to fill before flushing. |
| `queue-capacity` | `50000` | Bounded accept queue; over-capacity saves are rejected (back-pressure), not blocked. |
| `flush-threads` | `1` | Fixed at 1 (single-writer ordering). Any other value fails binding with a clear error. |
| `shutdown-drain-timeout-ms` | `5000` | Graceful-drain window on shutdown. Keep `≥ max-linger-ms`. |
| `retry-max-attempts` | `3` | Per-batch attempts on a transient IoTDB error. |
| `retry-initial-backoff-ms` | `50` | Initial retry backoff. |
| `retry-max-backoff-ms` | `1000` | Maximum retry backoff (exponential between the two). |

### Read path (`iotdb.ts.read.*`)

These also size the bounded executor used by the latest and attribute DAOs.

| Property | Default | Notes |
| --- | --- | --- |
| `threads` | `4` | Bounded read/IO executor size. |
| `queue-capacity` | `10000` | Read/IO task queue bound; an over-capacity task is rejected with a queue-full error. |

### Attributes (`iotdb.attributes.*`) — only when the attribute DAO is active

| Property | Default | Notes |
| --- | --- | --- |
| `iotdb.attributes.cluster-mode` | *(empty)* | Required when `database.attributes.type=iotdb-table`. Must be `sticky-routing` (writes for an identity pinned to one node) or `disabled` (single-node / acknowledged best-effort). Any other value, including the empty default, fails fast at startup, because the per-identity write lock converges writes only within a single JVM. (The module also accepts the equivalent `iotdb.attributes.cluster_mode` spelling.) |

## 7. Retention / TTL

Physical retention is a **table-level** IoTDB property, expressed in
milliseconds, set by the operator on the schema — not a per-data-point setting.
The shipped `telemetry` table is declared `WITH (TTL=DEFAULT)` (inherit the
database default, which is `INF` on a fresh node). To enable a concrete
retention, edit the schema before bootstrap or run
`ALTER TABLE telemetry SET PROPERTIES TTL=<milliseconds>` on the live table.

Two consequences to be aware of:

- The per-save `ttl` argument carried by `TimeseriesDao.save(..., ttl)` is **not**
  applied as physical retention per row — IoTDB Table Mode TTL is table-wide. The
  module uses the per-save `ttl` (and `iotdb.default-ttl-ms`) only for
  ThingsBoard's storage data-point accounting.
- The `telemetry_latest` overlay is declared `WITH (TTL='INF')` and is exempt
  from the `telemetry` TTL, so a retention window on `telemetry` does not evict
  overlay rows. Operators who need to bound the overlay must set a TTL on
  `telemetry_latest` separately.

See the README's *Retention / TTL* section for the full mechanism and the
syntax IoTDB accepts.

## 8. Operational notes

- **Back-pressure.** The write accept queue (`iotdb.ts.save.queue-capacity`) is
  bounded. When it is full, a `save` is rejected with a queue-full error rather
  than blocking the caller — size the queue for your ingest rate.
- **Retries.** A batch that hits a transient IoTDB condition (connection error,
  or a server-side overload / timeout / dispatch status) is retried up to
  `retry-max-attempts` with exponential backoff. Permanent errors (parse, type,
  schema) fail fast without consuming the retry budget.
- **Graceful shutdown.** On shutdown the writer stops accepting new saves and
  drains already-accepted writes within `iotdb.ts.save.shutdown-drain-timeout-ms`.
  If the window expires, the remaining pending writes are failed. Shutdown is
  at-least-once: if the timeout races an insert that has already started, the
  write may still commit after its future has failed, so tolerate a duplicate or
  uncertain final batch.
- **Session pool sizing.** Keep `iotdb.session-pool-size` at least as large as
  the sum of read threads and flush threads (see [§6](#6-configuration-reference)).

## 9. Known limitations (Phase-1 residuals)

These are documented behaviors of the current module, not defects to work around
silently:

- **Latest/attribute `version` is always `null`.** IoTDB has no SQL sequence, so
  `saveLatest`, attribute `save`, and `removeAllWithVersions` return a `null`
  version (type-correct, matching the Cassandra backend). ThingsBoard
  notifications that key off a non-null version are therefore not driven.
- **Same-timestamp cross-store type change.** The writer collapses duplicate
  `(tenant, entity, key, timestamp)` saves within a single flush, but it does not
  defend against a same-timestamp save whose value *type* changes across two
  separate flushes. Because each typed value lands in its own column, that single
  point can end up with two non-null typed columns. The behavior is fail-fast,
  not silent: a read of that one point throws rather than returning a wrong value;
  every other point is unaffected. In the latest path, the overlay wins an exact
  timestamp tie.
- **Overlay growth.** `telemetry_latest` is `TTL='INF'` with no entity-level
  cleanup, so under unbounded key cardinality it grows without bound (one row per
  identity; bounded for normal key sets).
- **Latest delete eventual consistency.** A purely telemetry-derived (full-save)
  latest can transiently still be read from `telemetry` until the separate
  historical delete commits.
- **Single-JVM write convergence.** The per-identity locks that serialize the
  latest-overlay and attribute writes are in-JVM, so cross-node single-writer
  safety is the operator's responsibility. The attribute path makes this explicit
  via the required `iotdb.attributes.cluster-mode` acknowledgement.
- **Unsupported SPI operations.** The attribute relational-migration helper
  `findNextBatch` throws `UnsupportedOperationException`: it is a relational
  keyset-pagination helper with no IoTDB equivalent, and it is the only method in
  the module that throws. The batch latest read (`findLatestByEntityIds` /
  `…Async`, new in ThingsBoard 4.3.1.2) instead returns empty, matching
  `CassandraBaseTimeseriesLatestDao` — it backs the `includeSamples` branch of
  `POST /api/entitiesQuery/find/keys`, where a synchronous throw would surface as
  an HTTP 500. A specific (non-null) device-profile key lookup likewise returns
  an empty list.
