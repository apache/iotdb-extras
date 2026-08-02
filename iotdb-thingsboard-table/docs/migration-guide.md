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

# Migration Guide: ThingsBoard storage on IoTDB Table Mode

This guide is for an operator who wants to move a ThingsBoard deployment's
telemetry, latest-value, and (optionally) entity-attribute storage onto Apache
IoTDB 2.x Table Mode using the `iotdb-thingsboard-table` module. It covers
version pairing, step-by-step enablement, the ThingsBoard-to-IoTDB data-model
mapping, coexistence with the existing storage backend, rollback, and the
limitations to plan for.

For configuration reference and the full limitation list see the module
[`README.md`](../README.md); for day-to-day operation see
[`docs/user-guide.md`](user-guide.md). This guide focuses on the one-time
cut-over.

## Audience and scope

The module is a ThingsBoard DAO backend built on IoTDB's table-session API. It
adds three independently activated routes:

- **historical telemetry** (`TimeseriesDao`) → the `telemetry` table;
- **latest value** (`TimeseriesLatestDao`) → derived from `telemetry` plus a
  per-key `telemetry_latest` overlay table;
- **entity attributes** (`AttributesDao`, opt-in) → the `entity_attributes`
  table.

**The module is additive and default-inert.** Putting the jar on the ThingsBoard
classpath changes nothing on its own: every route is gated behind an explicit
activation selector, and with no selector set the activation conditions all
return `false`, no IoTDB bean or session pool is created, and ThingsBoard keeps
using its existing storage backend. Enabling a route is a deliberate
configuration step, described below.

The timeseries route implements time-bucketed aggregation over the IoTDB
`telemetry` table — both the fixed-width millisecond `date_bin` path and the
timezone-aware calendar buckets (`WEEK` / `WEEK_ISO` / `MONTH` / `QUARTER`) — in
addition to raw read, write, and delete. Aggregation is therefore **not** a
reason to keep the host backend for the telemetry route.

## Version pairing

Pin these versions so the module, the IoTDB client, and the IoTDB server line
up. The module overrides **no** shared reactor versions; the IoTDB client and
TsFile versions are inherited from the `iotdb-extras` parent reactor.

| Component | Coordinate / image | Version | Where it is fixed |
| --- | --- | --- | --- |
| Module | `org.apache.iotdb:iotdb-thingsboard-table` | `2.0.4-SNAPSHOT` (parent version) | module `pom.xml` `<parent>` |
| Parent reactor | `org.apache.iotdb:iotdb-extras-parent` | `2.0.4-SNAPSHOT` | module `pom.xml` `<parent>` |
| IoTDB session client | `org.apache.iotdb:iotdb-session` | `2.0.5` | inherited from parent `iotdb.version`; module declares the dependency with no `<version>` |
| TsFile | `org.apache.tsfile:tsfile` | `2.1.1` | inherited from parent `tsfile.version` (transitive of `iotdb-session`) |
| Guava | `com.google.guava:guava` | `32.1.2-jre` | inherited from parent `guava.version`; `provided` scope |
| Bean-validation API | `jakarta.validation:jakarta.validation-api` | `3.0.2` | module override (`jakarta.* ` namespace, `provided` scope) to match the Spring Boot 3 runtime host |
| ThingsBoard host | (ThingsBoard distribution) | `4.3.1.2` | module `pom.xml` `thingsboard.version`; SPI surface verified against this tag |
| IoTDB server (tested) | `apache/iotdb` standalone image | `2.0.8` | integration tests run against `apache/iotdb:2.0.8-standalone` |

Notes:

- The module compiles against the **2.0.5** table-session client; its
  integration tests exercise the real write path against an **IoTDB 2.0.8**
  standalone server, so the 2.0.5-client / 2.0.8-server RPC path is the
  validated pairing. Any IoTDB 2.x server that speaks the same table-session RPC
  is a candidate, but 2.0.8 is the version the module is tested against.
- The build parent itself targets an older Spring line; the module deploys into
  ThingsBoard 4.3.x, which runs Spring Boot 3.5.x / Spring 6 / JDK 17 (the
  `jakarta.*` namespace). That is why `jakarta.validation-api` is overridden to
  `3.0.2` and why the module requires **JDK 17+** to build and run. The
  Hibernate Validator provider is supplied by the ThingsBoard runtime classpath,
  not bundled by the module.
- If you cannot determine a version from this table, read it from the module
  `pom.xml` and the `iotdb-extras` parent `pom.xml` rather than assuming a value.

## Before you begin

- A reachable IoTDB 2.x server in **Table Mode** (the tested server is IoTDB
  2.0.8). Have its host, port, and credentials ready.
- A ThingsBoard 4.3.1.2 build you control (the module is consumed as a
  compile/runtime dependency of the ThingsBoard application; it is not a
  drop-in for a pre-built binary distribution).
- JDK 17+ for the ThingsBoard build that includes this module.
- Decide which routes you are enabling: telemetry only, telemetry + latest, and
  whether to opt in to the stretch attributes route.

## Activation selectors

Each route is controlled by its own selector. Until a route's selector is set,
that route stays inert.

| Route | Required properties | Default state |
| --- | --- | --- |
| Historical telemetry (raw read/write/delete) | `database.ts.type=iotdb-table` **and** `iotdb.ts.experimental-raw-only=true` | inert |
| Latest value | `database.ts.type=iotdb-table` **and** `database.ts_latest.type=iotdb-table` **and** `iotdb.ts.experimental-raw-only=true` | inert |
| Entity attributes (stretch) | `database.attributes.type=iotdb-table` (independent of the timeseries selectors); also requires `iotdb.attributes.cluster_mode` set to `sticky-routing` or `disabled` | inert |

The latest route deliberately also requires the timeseries selector: the latest
value is derived from the `telemetry` table that only the IoTDB writer
populates, so activating latest without the IoTDB write path would read from a
table the module never fills. The attribute selector is fully independent of the
timeseries selectors.

## Step-by-step enablement

### 1. Add the module to the ThingsBoard build

Build and install `org.apache.iotdb:iotdb-thingsboard-table` and add it as a
dependency of the ThingsBoard application so its classes are on the runtime
classpath. The module is a Spring Boot auto-configuration
(`IoTDBTableConfiguration`) registered through
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
so it activates without the host application having to component-scan
`org.apache.iotdb.extras`.

In the `iotdb-extras` reactor the module lives behind a named opt-in profile and
requires JDK 17+:

```bash
# from the iotdb-extras repository root
mvn -pl iotdb-thingsboard-table -am -P with-thingsboard clean install
```

At this point the jar is present but **inert** — no selector is set yet, so
ThingsBoard still uses its existing backend.

### 2. Bootstrap the schema

On startup the module runs an idempotent bootstrap (gated by
`iotdb.schema.bootstrap`, default `true`) that reads `schema-iotdb-table.sql`
from the classpath and creates the target database plus the `telemetry` and
`entity_attributes` tables on a fresh IoTDB before the first write. When the
latest route is active, a **second** bootstrap reads
`schema-iotdb-table-latest.sql` to create the `telemetry_latest` overlay table.
Both bootstraps use `CREATE ... IF NOT EXISTS`, so they are safe to re-run.

If you manage the schema out of band, set `iotdb.schema.bootstrap=false` and
apply `schema-iotdb-table.sql` (and `schema-iotdb-table-latest.sql` when the
latest route is enabled) yourself before first start.

### 3. Set the activation properties for the routes you want

Add the selectors from the table above to the ThingsBoard configuration. For
example, to route both historical telemetry and latest values to IoTDB:

```properties
database.ts.type=iotdb-table
database.ts_latest.type=iotdb-table
iotdb.ts.experimental-raw-only=true
```

To additionally opt in to the stretch attributes route (only on a ThingsBoard
build that exposes the attribute selector — no shipped release does yet, see
*Limitations*):

```properties
database.attributes.type=iotdb-table
iotdb.attributes.cluster_mode=sticky-routing
```

`iotdb.attributes.cluster_mode` is mandatory when the attribute route is active
and must be `sticky-routing` (per-identity writes pinned to one node) or
`disabled` (single-node / acknowledged best-effort); any other value, including
the empty default, fails construction fast, because the attribute write path
converges only within a single JVM.

### 4. Point the module at IoTDB and size the pools

Set the connection and pool properties (bound from `iotdb.*`):

```properties
iotdb.host=<iotdb-host>
iotdb.port=6667
iotdb.username=<iotdb-user>
iotdb.password=<iotdb-password>
iotdb.database=thingsboard
iotdb.session-pool-size=8
```

The defaults are `127.0.0.1:6667`, credentials `root`/`root`, database
`thingsboard`, and a session pool of `8`. `iotdb.database` must be a valid IoTDB
identifier (letter or underscore, then letters/digits/underscores). The write
path and read path have their own pools, tunable under `iotdb.ts.save.*` and
`iotdb.ts.read.*`:

- Write path: `iotdb.ts.save.batch-size` (default `500`),
  `iotdb.ts.save.max-linger-ms` (`20`), `iotdb.ts.save.queue-capacity`
  (`50000`), and retry/backoff settings. The writer uses a single dedicated
  flush worker, so `iotdb.ts.save.flush-threads` is fixed at `1` (any other
  value fails binding).
- Read path: `iotdb.ts.read.threads` (default `4`) and
  `iotdb.ts.read.queue-capacity` (`10000`).

### 5. Set retention (optional, table-level TTL)

Physical retention in IoTDB Table Mode is a **table-level** property expressed in
**milliseconds** as a bare long literal, or the keyword `INF` (never expire) /
`DEFAULT` (inherit the database default, which is `INF` on a fresh node). The
shipped `telemetry` table is created `WITH (TTL=DEFAULT)`. To enable a concrete
retention, either edit the schema before bootstrap, e.g. 7 days:

```sql
CREATE TABLE telemetry (...) WITH (TTL=604800000);
```

or change it at runtime on the live table:

```sql
ALTER TABLE telemetry SET PROPERTIES TTL=604800000;   -- 7 days, in ms
ALTER TABLE telemetry SET PROPERTIES TTL=DEFAULT;      -- back to the db default
```

Quoted numbers (`'604800000'`) and duration forms (`'7d'`) are rejected by IoTDB
2.0.8. The `telemetry_latest` overlay table is created `WITH (TTL='INF')` and is
**exempt** from the `telemetry` TTL: setting a retention window on `telemetry`
does not evict overlay rows, so bound the overlay separately if needed (see
*Limitations*).

## Data-model mapping

ThingsBoard's relational `ts_kv` stores one row per `(entity_id, key, ts)` with
the value in one typed column (`bool_v` / `long_v` / `dbl_v` / `str_v` /
`json_v`) and `key` dictionary-encoded. The IoTDB `telemetry` table keys each row
by a TAG tuple plus the built-in `time` column, with the value in the matching
FIELD column. Exactly one value column is non-null per row, preserving the
source shape.

### `ts_kv` → `telemetry`

| ThingsBoard `ts_kv` | IoTDB `telemetry` | IoTDB column kind |
| --- | --- | --- |
| `entity_id` | `entity_id` | TAG |
| *(entity type)* | `entity_type` | TAG |
| *(tenant)* | `tenant_id` | TAG |
| `key` (dictionary id → string) | `key` (resolved to the string key) | TAG |
| `ts` | `time` (built-in) | TIME |
| `bool_v` | `bool_v` (`BOOLEAN`) | FIELD |
| `long_v` | `long_v` (`INT64`) | FIELD |
| `dbl_v` | `double_v` (`DOUBLE`) | FIELD |
| `str_v` | `str_v` (`STRING`) | FIELD |
| `json_v` | `json_v` (`TEXT`) | FIELD |

### `ts_kv_latest` → `telemetry_latest`

Do **not** migrate `ts_kv_latest` as a separate store. The latest value is read
from **both** the historical `telemetry` table (derived, newest row per key) and
a minimal per-key `telemetry_latest` overlay, merged by the maximum timestamp
per key. Once history is in `telemetry`, the derived latest follows
automatically; the overlay is written by the module on every `saveLatest` going
forward. The overlay table has the same TAG tuple
(`entity_type`, `tenant_id`, `key`, `entity_id`) and FIELD columns as
`telemetry`, plus an explicit `time TIMESTAMP TIME` column.

### `attribute_kv` → `entity_attributes` (stretch route only)

Applies only if the attribute route is activated. Each identity tuple
`(tenant_id, entity_type, entity_id, attribute_scope, key)` holds exactly one
current row at `time = lastUpdateTs`, with one typed FIELD set.

| ThingsBoard attributes | IoTDB `entity_attributes` | IoTDB column kind |
| --- | --- | --- |
| `entity_id` | `entity_id` | TAG |
| *(entity type)* | `entity_type` | TAG |
| *(tenant)* | `tenant_id` | TAG |
| attribute scope | `attribute_scope` (`CLIENT_SCOPE` / `SERVER_SCOPE` / `SHARED_SCOPE`) | TAG |
| attribute key | `key` | TAG |
| `last_update_ts` | `time` | TIME |
| boolean / long / double / string / json value | `bool_v` / `long_v` / `double_v` / `str_v` / `json_v` | FIELD |

## Migrating existing data (backfill)

The module supplies the write/read DAO surface but not an ETL tool; the backfill
is operator-driven.

1. **Stand up IoTDB** in Table Mode and bootstrap the schema (Step 2). Set a
   concrete `telemetry` TTL now (Step 5) if you want retention, before
   backfilling.
2. **Backfill** historical `ts_kv` into `telemetry`: read from the source
   backend, resolve the `key` dictionary id to the string key, supply
   `tenant_id` / `entity_type` for each `entity_id`, and write via batched
   `Tablet` inserts using the value-column mapping above. The module's own write
   path batches 500 rows per flush, a reasonable starting batch size.
3. **Verify** a sample: spot-check historical reads and latest values for a set
   of `(entity, key)` pairs against the source before the cut-over.
4. **Switch** ThingsBoard to the IoTDB routes by setting the selectors (Step 3)
   and restart. New writes flow into `telemetry`; the derived latest is
   immediately correct because it reads the same table, and the overlay is
   written from the first new `saveLatest` onward.

Idempotency: re-running the backfill for the same `(tags, time)` overwrites the
typed column in place (IoTDB same-timestamp overwrite), so re-runs converge
rather than duplicate. A value whose **type** changed at the same timestamp is
subject to the same-timestamp limitation below.

For a zero-downtime cut-over, a dual-write or read-old / write-new bridge during
backfill is an operator concern beyond this guide.

## Coexistence and rollback

Each route is independently activated and guarded:

- **Independent activation.** Enabling telemetry does not enable attributes, and
  vice versa. You can route telemetry + latest to IoTDB while attributes stay in
  the host entity database (the default Phase-1 posture).
- **Fail-fast conflict guard.** When a route is enabled but a conflicting
  non-IoTDB host DAO bean of the same SPI type is also present, startup fails
  fast with a clear message rather than silently shadowing one DAO with another.
  The historical (`TimeseriesDao`), latest (`TimeseriesLatestDao`), and attribute
  (`AttributesDao`) routes each have their own guard. Make sure the host backend
  for a route is removed/disabled when you point that route at IoTDB.

### Rollback

To roll a route back, **unset its selector(s)** and restart. The route's
activation condition then returns `false`, the IoTDB DAO and its session pool are
not created, the conflict guard does not fire, and the host backend's DAO
resumes serving that route.

What is and isn't reversible:

- **Configuration is fully reversible.** Unsetting the selectors returns
  ThingsBoard to the host backend with no schema changes required on the IoTDB
  side; the IoTDB tables simply stop receiving new writes.
- **Data written to IoTDB stays in IoTDB.** Rolling back the configuration does
  not copy IoTDB-resident telemetry/latest/attributes back into the host
  backend. If the host backend was serving the route during the IoTDB window,
  those two stores will have diverged for that period. Plan a reverse backfill
  (or a dual-write window) if you need the host backend to be current after a
  rollback.

## Limitations to plan for

These are the documented current-scope limitations (see the DAO Javadoc and the
module `README.md` for the authoritative list):

- **`version` is always `null`** on the latest and attribute routes. IoTDB has no
  SQL sequence (same as the Cassandra backend); this is type-correct and
  contract-legal, but ThingsBoard notifications that key off a non-null version
  are not driven in Phase-1.
- **Same-timestamp cross-store type change.** A single
  `(tenant, entity, key, timestamp)` point whose value *type* changes between two
  separate flushes can land two non-null typed columns. The behavior is
  **fail-fast, not silent**: a raw read of that one point throws an
  `IllegalStateException` rather than returning a wrong value; every other point
  is unaffected. The latest overlay wins an exact-timestamp tie, continuing the
  same limitation on the latest route.
- **Overlay TTL=`INF` growth.** `telemetry_latest` never physically expires and
  has no entity-level cleanup, so under unbounded key cardinality it grows
  without bound (one row per identity; bounded for normal key sets). Bound it
  separately with a TTL on `telemetry_latest` if needed.
- **Per-save `ttl` is not honored as physical retention.** The
  `TimeseriesDao.save(..., long ttl)` per-data-point TTL cannot be reconciled
  with table-wide IoTDB TTL; the module uses it only for ThingsBoard's
  storage-accounting, never as a physical-retention directive. Set physical
  retention on the table (Step 5).
- **The attributes route is a stretch / Phase-2 opt-in.** No shipped ThingsBoard
  release exposes a `database.attributes.type` selector yet (open question,
  tracked upstream), so in a real Phase-1 deployment the selector is unset and
  attributes stay in the host entity database. When activated, `save` is a
  non-atomic tag-only delete-then-insert under a per-identity in-JVM lock that
  converges only within one JVM; `findNextBatch` is unsupported
  (`UnsupportedOperationException`), and `findAllKeysByDeviceProfileId` with a
  non-null profile returns an empty list (matching the non-relational backend).

## Verifying the cut-over

After switching a route to IoTDB:

- Confirm the target tables exist and carry data: `SHOW TABLES` in the IoTDB
  database, and spot-check rows in `telemetry` (and `telemetry_latest` when the
  latest route is on).
- Confirm the effective TTL via `information_schema.tables` (the `ttl(ms)`
  column) or `SHOW TABLES` (the `TTL(ms)` column) if you set retention.
- Read back a few `(entity, key)` latest values through ThingsBoard and compare
  against the pre-cut-over source sample.
