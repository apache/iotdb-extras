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

# IoTDB ThingsBoard Table

## Overview

`iotdb-thingsboard-table` is a ThingsBoard historical-telemetry DAO backend
built on Apache IoTDB Table Mode. It lets a ThingsBoard deployment store
and serve time-series telemetry through IoTDB's table-session API instead of the
default Cassandra/SQL backends. It compiles against the reactor's IoTDB 2.0.5
table-session client; its integration tests run the real write, read,
aggregation, latest-telemetry, attribute and retention paths against an
`apache/iotdb:2.0.8-standalone` server. The module targets ThingsBoard v4.3.1.2. Because
it compiles with Java 17 language features (records and others), the
`iotdb-extras` parent reactor builds and tests it only on JDK 17+ through the
explicit, named `with-thingsboard` opt-in profile (it is not JDK-auto-activated):
CI passes `-P with-thingsboard` on the JDK 17/21 jobs while the 8/11 jobs omit it
and skip the module, and a plain reactor build never pulls it in.

## ThingsBoard SPI surface (Strategy F)

The ThingsBoard DAO SPI and value types (`org.thingsboard.*`) are not published
to Maven Central, so they cannot be a normal compile dependency. Strategy F
treats them as a **compile-only source surface** under `src/provided/java`: just
enough of the ThingsBoard interfaces and value objects to compile against. The
maven-jar-plugin excludes `org/thingsboard/**` from the built jar, so these
compile-only types never ship and never shadow the real ThingsBoard classes. At
runtime the actual ThingsBoard classpath supplies them. This keeps the module
buildable in isolation while binding to the genuine ThingsBoard types on a real
deployment.

The compile-only surface under `src/provided/java` was manually verified against
ThingsBoard `v4.3.1.2` (commit `c37fb509`): the `TimeseriesDao` SPI methods the
DAO consumes and the value-object accessors it reads were checked against the
upstream sources. A fully-automated check against the upstream artifact is not
possible because ThingsBoard's `dao`/`common-data` modules are not published to
Maven Central (the reason for Strategy F). As a guard against silent drift,
`StrategyFContractTest` pins the exact `TimeseriesDao` SPI method signatures the
DAO depends on, so any accidental edit to the local surface fails the build.

## Scope

The module is an inert-by-default foundation — `IoTDBTableBaseDao` (session-pool
lifecycle, schema/table bootstrap) — plus three DAOs, each behind its own
selector:

- `IoTDBTableTimeseriesDao`: the historical write path (`save`), raw read,
  delete, and time-bucketed aggregation — both the fixed-width millisecond
  `date_bin` path and the timezone-aware calendar path (`WEEK` / `WEEK_ISO` /
  `MONTH` / `QUARTER`). Enabled by `database.ts.type=iotdb-table` together with
  `iotdb.ts.experimental-raw-only=true`. That property name predates the
  aggregation support and is kept for compatibility; it is the opt-in for this
  backend as a whole, not a raw-only switch.
- `IoTDBTableLatestDao`: latest telemetry, derived from `telemetry` with a
  `telemetry_latest` overlay. Enabled by `database.ts_latest.type=iotdb-table`
  (see the latest-telemetry section below).
- `IoTDBTableAttributesDao`: entity attributes, **inert by default**. Enabled
  only by the `database.attributes.type=iotdb-table` opt-in, which is separate
  from the two timeseries selectors.

> **This is an incremental / experimental backend.** Nothing routes through
> IoTDB Table Mode unless the matching selector is set explicitly; with no
> selectors set the module does nothing.

## Entity attributes (inert by default)

`IoTDBTableAttributesDao` is an `AttributesDao` implementation for the IoTDB Table
Mode backend, storing entity attributes in the `entity_attributes` table. It is
**inert by default**: in Phase-1, entity attributes stay in the host entity
database (PostgreSQL), and this DAO only activates when an operator opts in
explicitly with `database.attributes.type=iotdb-table`.

This attribute selector is **independent** of `database.ts.type` /
`database.ts_latest.type` — the attribute DAO routes separately from the
time-series DAOs (a piggy-back on the timeseries selector was deliberately
rejected). Leaving it unset is the default posture: the activation condition
stays false, no attribute bean or session pool is created, and attributes keep
flowing to the host entity-DB `AttributesDao`.

`database.attributes.type` is a selector this module supplies rather than one
ThingsBoard offers. ThingsBoard switches its timeseries DAOs by configuration but
has no equivalent for attributes — at v4.3.1.2 its `JpaAttributeDao` is an
unconditional `@Component`, so no property can stand it down. Setting this
selector therefore has the module withdraw that one bean at startup, matched on
both its bean name and its fully-qualified class name, logging a WARN that names
it; any other competing `AttributesDao` fails startup untouched. See
`docs/user-guide.md` for the full semantics and their boundary.

When activated, each identity tuple
`(tenant_id, entity_type, entity_id, attribute_scope, key)` holds exactly one
current row: `save` is a tag-only `DELETE` (no time predicate) followed by an
`INSERT` at `time = lastUpdateTs` with exactly one typed FIELD set, both under a
per-identity in-JVM lock so concurrent same-identity writes converge to a single
row.

### Phase-1 attribute limitations (documented, not silently degraded)

- **No SQL sequence, so versions are best-effort.** `removeAllWithVersions`
  returns a `null` version (type-correct; the ThingsBoard service null-checks it
  before the EDQS attribute-delete notification, so that notification is not driven
  in Phase-1). `save`, however, returns a **non-null** version — the attribute's
  `lastUpdateTs` — because `BaseAttributesService#doSave` passes the version into
  `AttributeKv(..., long)` with no null-check, so a `null` would unbox to a
  `NullPointerException` on every save. `lastUpdateTs` is a per-identity-monotonic
  version proxy (stable across restarts).
- **`findNextBatch` is unsupported.** It is a relational keyset-pagination
  migration helper with no IoTDB equivalent; it throws
  `UnsupportedOperationException`.
- **`findAllKeysByDeviceProfileId` with a non-null profile returns an empty
  list**, matching the official non-relational backend:
  `CassandraBaseTimeseriesLatestDao.findAllKeysByDeviceProfileId` also returns
  `Collections.emptyList()`, since a NoSQL / time-series store cannot do the
  cross-device-table profile-dimension join. `entity_attributes` has no
  `device_profile_id` tag and the module has no device→profile lookup. The sole
  upstream caller is `DeviceProfileController`
  `GET /api/deviceProfile/devices/keys/attributes` (TENANT_ADMIN, a config-time UI
  key enumeration) which tolerates an empty result; failing loud would 500 under
  IoTDB while Cassandra returns empty. The null-profile path returns the
  tenant-wide distinct keys. A real device→profile lookup is a Phase-2 optional
  enhancement.
- **`save` is non-atomic.** The tag-only `DELETE`-then-`INSERT` is two separate
  statements with no rollback (IoTDB has no multi-statement transaction): if the
  `INSERT` fails after the `DELETE`, the prior value is lost (the future fails
  loud; the caller retries). Delete-first is **required** — an IoTDB insert at an
  existing `(tags, time)` merges typed columns, so a same-timestamp type change
  would otherwise leave two typed columns (the B1 fail-fast); insert-first would
  re-break that, so the order is not reversed. A concurrent same-identity point
  `find` takes the same per-identity lock and so never observes the transient empty
  window, but full-scope reads (`find`-by-keys / `findAll` /
  `findLatestByEntityIdsAndScope`) are best-effort (unlocked).
- **Single-JVM convergence only.** The per-identity lock converges writes only
  within one JVM; cross-node single-writer safety is the operator's
  responsibility, acknowledged via `iotdb.attributes.cluster_mode` (must be
  `sticky-routing` or `disabled` when the DAO is active, else construction fails
  fast).

## Latest telemetry (derived + overlay)

`IoTDBTableLatestDao` is a `TimeseriesLatestDao` implementation that serves the
latest value per `(tenant, entity, key)`. It activates only when all of
`database.ts.type=iotdb-table`, `database.ts_latest.type=iotdb-table`, and
`iotdb.ts.experimental-raw-only=true` are set (the timeseries selector is
required because the derived latest reads the `telemetry` table that only the
IoTDB writer populates). When it activates, `iotdb.ts_latest.cluster_mode` must
also be set to `sticky-routing` or `disabled` (mirroring `iotdb.attributes.cluster_mode`);
the empty default fails construction fast, because the overlay's per-identity lock
converges only within a single JVM.

The latest value is read from **both** the historical `telemetry` table
(derived, `ORDER BY time DESC LIMIT 1` / `LAST_BY(col, time)`, engine-accelerated
by IoTDB's native last cache) **and** a minimal per-key `telemetry_latest`
**overlay** table, merged by the maximum timestamp per key (the overlay wins an
exact tie). The merge is max-by-ts, not additive, so a key present in both stores
is never double-counted.

**The overlay is written on every `saveLatest`**. The module's historical `save()`
write path is asynchronous and batched,
so at the moment `saveLatest` runs it cannot see whether a paired `telemetry` row
will be written. It therefore cannot distinguish a latest-only write (e.g. the
EntityView telemetry-copy `LATEST_AND_WS` / `saveTs=false` path, which calls
`saveLatest` with **no** paired `save()`) from a normal full-save, and writes the
overlay unconditionally as a delete-then-insert (one row per identity, under a
per-identity in-JVM lock). This **closes the latest-only data-loss gap** that a
no-shadow-table no-op would otherwise drop, at the cost of one extra overlay write
per latest update (equivalent to the standard ThingsBoard latest-table behavior).
`removeLatest` reads the derived and overlay latest **separately** under the
per-identity lock and, when the merged latest is inside the half-open
`[startTs, endTs)` delete window, deletes the overlay row **only if the overlay's
own timestamp is in-window** (an out-of-window overlay value survives as the next
latest rather than being wiped). When `rewriteLatestIfDeleted` is set it resurrects
the next-older value across **both** stores (`telemetry` where `time < startTs` and
the overlay row if its own ts is `< startTs`; max-ts-wins) and returns it as the
removing result's data (so TB emits a WS update rather than a delete); a prior that
is the overlay's own already-stored value is reported without a redundant rewrite.

### Residual latest limitations

- **`version` is always `null`.** IoTDB has no SQL sequence (same as Cassandra);
  type-correct and contract-legal, but TB notifications that key off a non-null
  version are not driven in Phase-1.
- **Telemetry-derived race residual.** Overlay-backed values are race-free under
  the per-identity lock, but the historical `remove` runs as a separate future
  from `removeLatest`, so a purely telemetry-derived (full-save) latest can
  transiently still be read from `telemetry` until that historical delete commits
  (eventually consistent).
- **Overlay growth.** `telemetry_latest` is `TTL='INF'` with no entity-level
  cleanup, so under unbounded key cardinality it grows without bound (one row per
  identity; bounded for normal key sets).
- **Same-timestamp cross-store type change.** The overlay wins an exact-ts tie,
  continuing the documented same-timestamp (B1) limitation below.
- **Batch latest read deferred (graceful, not throwing).**
  `findLatestByEntityIds(Async)` (new in v4.3.1.2) returns an **empty list**
  rather than throwing, because it is reachable in normal operation and a throw
  would surface as an HTTP 500. This matches the official
  `CassandraBaseTimeseriesLatestDao`; the derived batch read is a follow-up.
  Key discovery (`findAllKeysByEntityIds(Async)` and the tenant-wide variant) is
  implemented: it collects `DISTINCT key` from **both** `telemetry` and the
  `telemetry_latest` overlay, so a key that only ever reached the overlay through
  `saveLatest` is discovered too.

The `telemetry_latest` table is created on startup by a **second** idempotent
schema bootstrap (`schema-iotdb-table-latest.sql`), registered only when the
latest selector is active and `iotdb.schema.bootstrap` is not disabled; when the
latest selector is off the overlay table is never created.

## Retention / TTL

Physical retention is a **table-level** IoTDB property that the operator sets on
the schema; it is not a per-data-point setting. IoTDB Table Mode expresses TTL as
a retention window in **milliseconds**, and the accepted forms are narrow:

| Form | Meaning |
| --- | --- |
| `TTL=604800000` | A concrete retention: a bare, **unquoted** long literal (milliseconds). |
| `TTL='INF'` | Never expire. The **quoted** string is the only accepted spelling; this is the form `entity_attributes` and `telemetry_latest` ship with. |
| `TTL=DEFAULT` | Inherit the database default, which is `INF` on a fresh node. |

Anything else is rejected by IoTDB 2.0.8: an unquoted `TTL=INF` is parsed as an
identifier (`ttl value must be a LongLiteral, but now is Identifier`), and any
other quoted value — including a quoted number (`'604800000'`) or a duration
(`'7d'`) — fails with `ttl value must be 'INF' or a long literal`.

The shipped `schema-iotdb-table.sql` declares `telemetry` with
`WITH (TTL=DEFAULT)`, so a fresh deployment never expires data until an operator
chooses otherwise. To set a concrete retention, either edit the schema before the
first bootstrap:

```sql
CREATE TABLE telemetry (...) WITH (TTL=604800000);
```

or change it at runtime on the live table:

```sql
ALTER TABLE telemetry SET PROPERTIES TTL=604800000;   -- 7 days, in ms
ALTER TABLE telemetry SET PROPERTIES TTL=DEFAULT;     -- back to the db default
```

The effective value can be read back from `SHOW TABLES`, which reports it in a
`TTL(ms)` column, or from `information_schema.tables`, where the column must be
quoted because its name contains parentheses:

```sql
SELECT table_name, "ttl(ms)" FROM information_schema.tables WHERE database='thingsboard';
```

Either way a never-expiring table reads back as `INF` and a concrete retention as
the millisecond number.
`IoTDBTableTtlIT` pins both paths against a real IoTDB 2.0.8 container. It
verifies the TTL **property mechanism** only and deliberately does not assert
physical row eviction, because eviction is asynchronous and compaction-driven and
so is not deterministic inside a test.

The `telemetry_latest` overlay is declared `TTL='INF'` on purpose: it holds at
most one row per identity and is the authority for a latest value, so expiring it
would silently drop the latest reading while history remained.

- **Phase-1 limitation: the per-save `ttl` argument is not a physical-retention
  directive.** ThingsBoard's `TimeseriesDao.save(..., long ttl)` carries a
  per-data-point TTL, but IoTDB Table Mode retention can only be expressed at the
  table level, so the two cannot be reconciled faithfully. The module therefore
  uses that argument (together with `iotdb.defaultTtlMs`) **only** for
  ThingsBoard's storage data-point accounting, and never as an instruction to
  expire rows. Operators who need physical retention set it on the table as shown
  above.

## Known limitations

**Same-timestamp type change across separate flushes.** The writer collapses
duplicate `(tenant, entity, key, timestamp)` saves *within a single flush* so the
last write wins, but it does not yet defend against a same-`(tenant, entity, key,
timestamp)` save whose value *type* changes between two **separate** flushes (for
example a `LONG` written in one flush and a `STRING` written at the same
timestamp in a later flush). Because each typed value lands in its own column
(`long_v`, `str_v`, ...), that single point can end up with two non-null typed
columns.

This is a deliberate current-scope decision: the cleanup that would prevent it (a
delete-then-insert overwrite on every save) is outside the current scope. The
behavior is **fail-fast, not silent**: a raw read of that one poisoned point
throws an `IllegalStateException` (the single-typed-column invariant enforced in
`IoTDBTableBaseDao`) rather than returning a wrong value. Every other point is
unaffected. This documented behavior is pinned by an integration test
(`IoTDBTableTimeseriesDaoIT`).

## Configuration

The backend is bound from `iotdb.*` Spring properties (see `IoTDBTableConfig`).
Key activation and operational flags:

| Property | Default | Meaning |
| --- | --- | --- |
| `database.ts.type` | _(unset)_ | Set to `iotdb-table` as the ThingsBoard historical-timeseries backend selector. |
| `iotdb.ts.experimental-raw-only` | `false` | Explicit opt-in for this backend. Must be `true` together with `database.ts.type=iotdb-table`. The name predates the aggregation support and is kept for compatibility: write, raw read, delete **and** time-bucketed aggregation are all served when it is enabled. |
| `database.attributes.type` | _(unset)_ | Set to `iotdb-table` to opt in to the entity-attribute DAO. Independent of the timeseries selectors. Unset by default, and while unset the attribute DAO is inert. Setting it withdraws ThingsBoard's own `jpaAttributeDao` bean — see the Entity attributes section. |
| `iotdb.attributes.cluster_mode` | _(empty)_ | Required when `database.attributes.type=iotdb-table`. Must be `sticky-routing` (per-identity writes pinned to one node) or `disabled` (single-node / acknowledged best-effort); any other value (including the empty default) fails construction fast, because the attribute write path converges only within a single JVM. |
| `iotdb.ts_latest.cluster_mode` | _(empty)_ | Required when `database.ts_latest.type=iotdb-table` (the latest-overlay DAO is active). Must be `sticky-routing` (per-identity latest writes pinned to one node) or `disabled` (single-node / acknowledged best-effort); any other value (including the empty default) fails construction fast, because the latest-overlay write path converges only within a single JVM. This is the symmetric acknowledgement to `iotdb.attributes.cluster_mode`. |
| `iotdb.attributes.executor.threads` | `4` | Worker-thread count for the attribute DAO's bounded IO executor. Sized independently of `iotdb.ts.read.*` so the attribute path's concurrency can be tuned on its own; the default matches `iotdb.ts.read`. |
| `iotdb.attributes.executor.queue-capacity` | `10000` | Bounded task-queue capacity for the attribute DAO's IO executor; a full queue rejects fast (back-pressure) rather than growing unboundedly. Default matches `iotdb.ts.read`. |
| `iotdb.host` / `iotdb.port` | `127.0.0.1` / `6667` | IoTDB node address. |
| `iotdb.username` / `iotdb.password` | `root` / `root` | IoTDB credentials. |
| `iotdb.database` | `thingsboard` | Target IoTDB database. |
| `iotdb.session-pool-size` | `8` | Table session pool size. |
| `iotdb.schema.bootstrap` | `true` | When `true`, the module runs an idempotent startup bootstrap that reads `schema-iotdb-table.sql` from the classpath and creates the `telemetry` / `entity_attributes` tables (and database) on a fresh IoTDB before the first write. When the latest selector is active it also runs a second bootstrap from `schema-iotdb-table-latest.sql` to create the `telemetry_latest` overlay table. Set to `false` if you manage the schema out-of-band. |

The module is a Spring Boot **auto-configuration** (`IoTDBTableConfiguration`).
Its deployment host is ThingsBoard 4.3.x, which runs on Spring Boot 3.5.x, so the
active registration is
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
(paired with the `@AutoConfiguration` annotation). The legacy
`META-INF/spring.factories` `EnableAutoConfiguration` entry is retained only so the
module still activates if it is ever consumed by a Spring Boot 2.7 host; on Boot 3.x
it is ignored. Either way it activates in a real ThingsBoard deployment without the
host application having to component-scan `org.apache.iotdb.extras`.

## Build

The module is an explicit, named opt-in profile (`with-thingsboard`), so it is built
only when that profile is activated (a plain reactor build never pulls it in). It
requires JDK 17+:

```bash
# from the iotdb-extras repository root
mvn -pl iotdb-thingsboard-table -am -P with-thingsboard clean test
```

It can also be built standalone from the module directory:

```bash
cd iotdb-thingsboard-table
mvn compile -DskipTests
```

## Test

Run the Java test scaffold from the module directory:

```bash
mvn test
```

Run the Docker-backed integration tests only when required:

```bash
mvn -Piotdb-table-it verify
```

Start the local integration stack with explicit environment values:

```bash
TB_POSTGRES_USER=<postgres-user> TB_POSTGRES_PASSWORD=<postgres-password> \
  IOTDB_USERNAME=<iotdb-user> IOTDB_PASSWORD=<iotdb-password> \
  docker compose -f docker-compose.test.yml up -d
```

Stop and remove the local stack:

```bash
docker compose -f docker-compose.test.yml down -v
```

## Status

`IoTDBTableBaseDao` plus the `IoTDBTableTimeseriesDao` write, raw-read, delete
and time-bucketed aggregation paths are implemented behind
`database.ts.type=iotdb-table` and `iotdb.ts.experimental-raw-only=true`.
Without both properties, that DAO is inert. `IoTDBTableLatestDao` (latest
telemetry, with the `telemetry_latest` overlay and key discovery) is implemented
behind its own `database.ts_latest.type=iotdb-table` selector. Physical
retention is a table property the operator sets on the schema; see
Retention / TTL above.

`IoTDBTableAttributesDao` is **inert by default** and activated only by the
`database.attributes.type=iotdb-table` opt-in, which is separate from the two
timeseries selectors (see the Entity attributes section above and its Phase-1
limitations). While the selector is unset — the default posture — the attribute
DAO never activates and attributes stay in the host entity database. Setting it
has the module withdraw ThingsBoard's own attributes bean, which is why that
section describes the matching rule and its boundary.
