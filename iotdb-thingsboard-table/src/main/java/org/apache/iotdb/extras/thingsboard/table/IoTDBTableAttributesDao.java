/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.extras.thingsboard.table;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Striped;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;
import org.springframework.beans.factory.DisposableBean;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.util.TbPair;
import org.thingsboard.server.dao.attributes.AttributesDao;
import org.thingsboard.server.dao.model.sql.AttributeKvEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Entity-attribute DAO for the IoTDB Table Mode backend.
 *
 * <p>Spring activation: {@code database.attributes.type=iotdb-table}. This is a selector this
 * module supplies rather than one upstream ThingsBoard offers -- upstream exposes no {@code
 * AttributesDao} selector of its own -- so the DAO is <b>inert by default</b>: while the property
 * is unset, {@link IoTDBTableAttributesEnabledCondition} stays false, the bean is never
 * instantiated, and attributes stay in the host entity DB. Setting it makes {@code
 * AttributesDaoConflictGuard} withdraw ThingsBoard's own attributes bean; see that guard's javadoc
 * for the matching rule and the boundary of what it can see. The selector is independent of {@code
 * database.ts.type} / {@code database.ts_latest.type} (the attribute DAO routes separately); if
 * upstream ever exposes a native selector, this module should use it instead.
 *
 * <p>This DAO is wired as an explicit {@code @Bean} in {@link IoTDBTableConfiguration} (guarded by
 * the activation property) rather than via component scanning, so the {@code ITableSessionPool}
 * constructor parameter is guaranteed to be created first; that avoids the
 * {@code @ConditionalOnBean} bean-definition ordering trap, where a component-scanned bean can be
 * condition-evaluated before the imported configuration's pool {@code @Bean} is registered.
 *
 * <p>Current-state (latest-only) contract on the historical-shaped {@code entity_attributes} table:
 * each identity tuple {@code (tenant_id, entity_type, entity_id, attribute_scope, key)} holds
 * exactly one current row, so:
 *
 * <ul>
 *   <li>{@link #save} is delete-then-insert: a tag-only {@code DELETE} (no time predicate) removes
 *       the identity across all time, then one row is inserted at {@code time =
 *       attribute.getLastUpdateTs()} with exactly one typed FIELD set. Delete-first is required: an
 *       IoTDB insert at an existing {@code (tags, time)} merges typed columns, so a same-timestamp
 *       type change would otherwise leave two typed columns — the same-timestamp two-type-column
 *       limitation (B1), enforced fail-fast. Both statements run under a per-identity in-JVM lock
 *       so concurrent same-identity writes converge to a single row. The write is non-atomic (IoTDB
 *       has no multi-statement transaction): an {@code INSERT} failure after the {@code DELETE}
 *       commits loses the prior value — see the limitations list.
 *   <li>{@code find}/{@code findAll} are synchronous single/multi-row {@code SELECT}s that run on
 *       the calling thread (the ThingsBoard service wraps them in {@code
 *       Futures.immediateFuture(...)}).
 *   <li>{@code removeAll}/{@code removeAllWithVersions} return one future per key, each a tag-only
 *       {@code DELETE}.
 * </ul>
 *
 * <p>The {@code COLUMN_NAMES}/{@code DATA_TYPES}/{@code COLUMN_CATEGORIES} arrays below follow the
 * {@code entity_attributes} DDL tag order in {@code schema-iotdb-table.sql} (attribute_scope,
 * entity_type, tenant_id, key, entity_id, then the five typed FIELDs). The {@code time TIMESTAMP
 * TIME} column is written via {@link Tablet#addTimestamp}, never as a {@code ColumnCategory.TIME}
 * tablet column, so the three parallel arrays cover exactly the 10 non-time columns and must stay
 * positionally aligned.
 *
 * <p>Every {@code SELECT}/{@code DELETE} predicate is keyed on the full identity (tenant_id +
 * entity_type + entity_id, plus {@code attribute_scope} where scope-scoped, plus {@code key}) — a
 * deliberate SUPERSET of ThingsBoard's relational {@code JpaAttributeDao}, which keys an attribute
 * row by the entity UUID alone. The extra tag predicates are required because {@code
 * entity_attributes} is a single multi-tenant / multi-entity IoTDB table; they prevent any
 * cross-tenant, cross-entity, or cross-scope read or delete.
 *
 * <p>Phase-1 honest limitations (documented, not silently degraded):
 *
 * <ul>
 *   <li>IoTDB has no SQL sequence. {@code removeAllWithVersions} returns a {@code null} version
 *       (type-correct; the ThingsBoard service null-checks it before the EDQS attribute-delete
 *       notification, so that notification is simply not driven in Phase-1). {@code save}, however,
 *       MUST return a NON-null version: {@code BaseAttributesService#doSave} passes it into {@code
 *       AttributeKv(EntityId, AttributeScope, AttributeKvEntry, long)} with no null-check, so a
 *       {@code null} would unbox to a {@code NullPointerException} on every save. It therefore
 *       returns the attribute's {@code lastUpdateTs} as a per-identity-monotonic version proxy
 *       (stable across restarts; EDQS integration otherwise stays out of Phase-1 scope, consistent
 *       with the latest DAO).
 *   <li>{@code findNextBatch} is a relational keyset-pagination migration helper with no IoTDB
 *       equivalent; it throws {@code UnsupportedOperationException}.
 *   <li>{@code findAllKeysByDeviceProfileId} with a non-null profile returns an empty list,
 *       matching the official non-relational backend ({@code
 *       CassandraBaseTimeseriesLatestDao.findAllKeysByDeviceProfileId} also returns {@code
 *       Collections.emptyList()}): {@code entity_attributes} has no {@code device_profile_id} tag,
 *       and the sole caller — {@code DeviceProfileController} {@code GET
 *       /api/deviceProfile/devices/keys/attributes} (TENANT_ADMIN, a config-time UI key
 *       enumeration) — tolerates an empty result. A real device→profile lookup is a Phase-2
 *       optional enhancement.
 *   <li>{@code removeAllByEntityId} is best-effort select-then-delete (IoTDB has no {@code DELETE
 *       ... RETURNING}); a key inserted between the select and the delete may be deleted but not
 *       reported.
 *   <li><b>Non-atomic delete-then-insert.</b> {@code save} runs a DELETE then an INSERT with no
 *       multi-statement transaction (IoTDB has none). Delete-first is required so a same-timestamp
 *       type change converges to one typed column (an insert at an existing {@code (tags, time)}
 *       merges columns); insert-first would re-break that, so the order cannot be reversed. An
 *       {@code INSERT} failure after the {@code DELETE} commits therefore loses the prior value (no
 *       derived store to recover an attribute from); the {@code save} future fails loud and the
 *       caller retries.
 *   <li><b>Best-effort multi-key reads.</b> The single-key {@code find} takes the per-identity lock
 *       so it never observes the delete→insert gap, but {@code find(keys)} / {@code findAll} /
 *       {@code findLatestByEntityIdsAndScope} read WITHOUT a lock, so a concurrent same-identity
 *       {@code save} can make a key transiently absent (between its {@code DELETE} and {@code
 *       INSERT}). Eventually consistent; bulk-read callers tolerate transient key-absence.
 *   <li><b>Unbounded predicate fan-out.</b> {@code find(keys)} builds a {@code key IN (...)} list
 *       and the entity-id methods build a {@code (entity_type=.. AND entity_id=..) OR ...} set with
 *       no chunking, so a very large key/entity set yields a large SQL string (bounded for normal
 *       scopes; chunking is a follow-up if large fan-outs appear).
 *   <li>The per-identity lock converges writes only within a single JVM; cross-node single-writer
 *       safety is the operator's responsibility, acknowledged via {@code
 *       iotdb.attributes.cluster_mode}.
 * </ul>
 */
@Slf4j
public class IoTDBTableAttributesDao extends IoTDBTableBaseDao
    implements AttributesDao, DisposableBean {

  static final String TABLE_NAME = "entity_attributes";

  // NUL is the identity-lock key separator: it cannot appear in any tenant/entity UUID,
  // entity-type/scope enum name, or telemetry key, so distinct identities can never collide
  // into the same Striped lock stripe by string concatenation.
  private static final char LOCK_KEY_SEPARATOR = '\u0000';
  private static final String SELECT_TYPED_COLUMNS =
      "time, bool_v, long_v, double_v, str_v, json_v";

  // The three parallel arrays below follow the entity_attributes DDL tag order
  // (schema-iotdb-table.sql): attribute_scope, entity_type, tenant_id, key, entity_id (TAGs),
  // then bool_v, long_v, double_v, str_v, json_v (FIELDs). They must stay positionally aligned and
  // cover exactly the 10 non-time columns; the `time TIMESTAMP TIME` column is written through
  // Tablet#addTimestamp (NOT a ColumnCategory.TIME entry), so COLUMN_CATEGORIES holds only TAG and
  // FIELD. Rebuilding with a different tag order is a correctness bug (TAG-order rot).
  private static final List<String> COLUMN_NAMES =
      List.of(
          "attribute_scope",
          "entity_type",
          "tenant_id",
          "key",
          "entity_id",
          "bool_v",
          "long_v",
          "double_v",
          "str_v",
          "json_v");
  private static final List<TSDataType> DATA_TYPES =
      List.of(
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.STRING,
          TSDataType.BOOLEAN,
          TSDataType.INT64,
          TSDataType.DOUBLE,
          TSDataType.STRING,
          TSDataType.TEXT);
  private static final List<ColumnCategory> COLUMN_CATEGORIES =
      List.of(
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.TAG,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD,
          ColumnCategory.FIELD);

  // Per-identity write serialization (single-JVM convergence).
  private final Striped<Lock> identityLocks = Striped.lock(256);
  // Per-ENTITY read/write serialization guarding entity-wide removeAllByEntityId against concurrent
  // single-identity save/delete. A per-key mutate (save/deleteIdentity) takes the entity READ lock
  // (shared: many keys of the same entity may proceed concurrently); removeAllByEntityId takes the
  // entity WRITE lock (exclusive) around its select+delete so no save can re-INSERT a key between
  // the entity-wide select and delete. Lock ordering is ALWAYS entity-lock (outer) then
  // per-identity lock (inner); removeAllByEntityId never takes an identity lock, so no cycle is
  // possible.
  private final Striped<ReadWriteLock> entityLocks = Striped.readWriteLock(256);

  public IoTDBTableAttributesDao(ITableSessionPool tableSessionPool, IoTDBTableConfig config) {
    super(tableSessionPool);
    // Section 3.5 cluster opt-in validator: when the attribute DAO is active the operator must
    // acknowledge cluster routing explicitly, because the delete-then-insert write path converges
    // only within a single JVM. This is intentionally independent of ts.type / ts_latest.type
    // (the attribute DAO routes separately). Fail fast at construction on an absent/invalid value.
    requireClusterModeAcknowledged(config.getAttributes().getClusterMode());
    // The attribute DAO activates independently of the timeseries selector, so its IO executor is
    // sized by its OWN config block (iotdb.attributes.executor.*), whose defaults equal the
    // ts.read defaults so behavior is unchanged unless an operator tunes them.
    initReadExecutor(
        config.getAttributes().getExecutor().getThreads(),
        config.getAttributes().getExecutor().getQueueCapacity(),
        config.getTs().getSave().getShutdownDrainTimeoutMs(),
        "iotdb-table-attributes-io-worker-",
        "IoTDB Table Mode attribute IO queue is full",
        "IoTDB Table Mode attribute DAO is shutting down");
  }

  // ---- save: delete-then-insert under a per-identity lock ----

  @Override
  public ListenableFuture<Long> save(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      AttributeKvEntry attribute) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(attributeScope, "attributeScope");
    Objects.requireNonNull(attribute, "attribute");
    String key = requireKey(attribute.getKey());
    Lock entityReadLock = entityLock(tenantId, entityId).readLock();
    Lock lock = identityLock(tenantId, entityId, attributeScope, key);
    return submitReadTask(
        () -> {
          // Lock ordering: entity read lock (outer) then per-identity lock (inner). The shared read
          // lock lets concurrent saves of different keys on the same entity proceed, while
          // excluding a concurrent entity-wide removeAllByEntityId (which holds the entity write
          // lock).
          entityReadLock.lock();
          try {
            lock.lock();
            try {
              doSave(tenantId, entityId, attributeScope, attribute, key);
            } finally {
              lock.unlock();
            }
          } finally {
            entityReadLock.unlock();
          }
          // The version MUST be non-null: ThingsBoard's BaseAttributesService#doSave passes it
          // straight into AttributeKv(EntityId, AttributeScope, AttributeKvEntry, long) with no
          // null-check, so a null would unbox to a NullPointerException on every attribute save
          // (the save() future would fail even though the row was written). IoTDB has no monotonic
          // sequence, so use the attribute's last-update timestamp as a per-identity-monotonic
          // version proxy: it is stable across restarts (unlike an in-JVM counter) and only drives
          // the EDQS update ordering. See the class javadoc.
          return attribute.getLastUpdateTs();
        });
  }

  private void doSave(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      AttributeKvEntry attribute,
      String key)
      throws Exception {
    String deleteSql = buildDeleteSql(tenantId, entityId, attributeScope, key);
    Tablet tablet = buildTablet(tenantId, entityId, attributeScope, attribute, key);
    try (ITableSession session = tableSessionPool.getSession()) {
      // Delete-then-insert (NOT insert-then-delete): the tag-only DELETE removes the identity
      // across
      // ALL time, then the single current row is inserted at time = lastUpdateTs. The delete must
      // run first because an IoTDB insert at an existing (tags, time) MERGES typed columns (a null
      // field does not overwrite an existing value), so a same-timestamp type change would
      // otherwise
      // leave two typed columns (the B1 fail-fast). The trade-off is a non-atomic write (IoTDB has
      // no multi-statement transaction): an INSERT failure after the DELETE commits loses the prior
      // value (the future fails loud; documented in the class limitations). Insert-first would
      // avoid
      // that loss but re-break the same-timestamp convergence, so it is deliberately not used.
      session.executeNonQueryStatement(deleteSql);
      session.insert(tablet);
    }
  }

  private Tablet buildTablet(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      AttributeKvEntry attribute,
      String key) {
    Tablet tablet = new Tablet(TABLE_NAME, COLUMN_NAMES, DATA_TYPES, COLUMN_CATEGORIES, 1);
    // entity_attributes declares an explicit `time TIMESTAMP TIME` column in DDL, but it is still
    // the table's time column: it is written through the normal tablet timestamp mechanism (NOT a
    // ColumnCategory.TIME entry). Use the attribute's last-update timestamp as the row time.
    tablet.addTimestamp(0, attribute.getLastUpdateTs());
    // TAG values, in the DDL tag order (attribute_scope, entity_type, tenant_id, key, entity_id).
    tablet.addValue("attribute_scope", 0, attributeScope.name());
    tablet.addValue("entity_type", 0, entityId.getEntityType().name());
    tablet.addValue("tenant_id", 0, tenantId.getId().toString());
    tablet.addValue("key", 0, key);
    tablet.addValue("entity_id", 0, entityId.getId().toString());
    // FIELD values: exactly one typed column is non-null, chosen by the attribute's DataType.
    DataType dataType = attribute.getDataType();
    tablet.addValue("bool_v", 0, dataType == DataType.BOOLEAN ? attribute.getValue() : null);
    tablet.addValue("long_v", 0, dataType == DataType.LONG ? attribute.getValue() : null);
    tablet.addValue("double_v", 0, dataType == DataType.DOUBLE ? attribute.getValue() : null);
    tablet.addValue("str_v", 0, dataType == DataType.STRING ? attribute.getValue() : null);
    tablet.addValue("json_v", 0, dataType == DataType.JSON ? attribute.getValue() : null);
    tablet.setRowSize(1);
    return tablet;
  }

  // ---- find / findAll: synchronous reads on the calling thread ----

  @Override
  public Optional<AttributeKvEntry> find(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String attributeKey) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(attributeScope, "attributeScope");
    String key = requireKey(attributeKey);
    // Take the same per-identity lock save() holds across its (non-atomic) delete+insert, so a
    // concurrent point-read observes either the old or the new value, never the transient empty gap
    // between the DELETE and the INSERT. Deadlock-safe: find takes ONLY the identity lock (never
    // the
    // entity lock), so it cannot form a cycle with the entity-then-identity ordering used by save()
    // and removeAllByEntityId(). Full-scope reads (find-by-keys / findAll) stay best-effort; see
    // the
    // README limitation.
    Lock lock = identityLock(tenantId, entityId, attributeScope, key);
    lock.lock();
    try {
      return doFind(tenantId, entityId, attributeScope, key);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read entity attribute", e);
    } finally {
      lock.unlock();
    }
  }

  private Optional<AttributeKvEntry> doFind(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String key)
      throws Exception {
    String sql = buildFindSql(tenantId, entityId, attributeScope, key);
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      if (!row.next()) {
        return Optional.empty();
      }
      // B1 fail-fast: getEntry throws IllegalStateException if more than one typed column is set.
      // delete-then-insert guarantees a single typed FIELD per identity, so this is defensive.
      TypedKvValue value = getEntry(row);
      if (!value.hasValue()) {
        return Optional.empty();
      }
      long ts = row.getTimestamp("time").getTime();
      return Optional.of(new BaseAttributeKvEntry(kvEntry(key, value), ts, null));
    }
  }

  @Override
  public List<AttributeKvEntry> find(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      Collection<String> attributeKeys) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(attributeScope, "attributeScope");
    Objects.requireNonNull(attributeKeys, "attributeKeys");
    if (attributeKeys.isEmpty()) {
      return List.of();
    }
    try {
      return doFindKeyed(buildFindKeysSql(tenantId, entityId, attributeScope, attributeKeys));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read entity attributes by keys", e);
    }
  }

  @Override
  public List<AttributeKvEntry> findAll(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(attributeScope, "attributeScope");
    try {
      return doFindKeyed(buildFindAllSql(tenantId, entityId, attributeScope));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read all entity attributes", e);
    }
  }

  private List<AttributeKvEntry> doFindKeyed(String sql) throws Exception {
    List<AttributeKvEntry> entries = new ArrayList<>();
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        String key = row.getString("key");
        TypedKvValue value = getEntry(row);
        if (!value.hasValue()) {
          continue;
        }
        long ts = row.getTimestamp("time").getTime();
        entries.add(new BaseAttributeKvEntry(kvEntry(key, value), ts, null));
      }
    }
    return entries;
  }

  // ---- removeAll / removeAllWithVersions: per-key future + tag-only DELETE ----

  @Override
  public List<ListenableFuture<String>> removeAll(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, List<String> keys) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(attributeScope, "attributeScope");
    Objects.requireNonNull(keys, "keys");
    // Validate every key before launching any delete task, so a blank key never leaves a partial
    // delete behind (fail-fast before any side effect).
    List<String> validatedKeys = new ArrayList<>(keys.size());
    for (String rawKey : keys) {
      validatedKeys.add(requireKey(rawKey));
    }
    List<ListenableFuture<String>> futures = new ArrayList<>(validatedKeys.size());
    for (String key : validatedKeys) {
      futures.add(
          submitReadTask(
              () -> {
                deleteIdentity(tenantId, entityId, attributeScope, key);
                return key;
              }));
    }
    return futures;
  }

  @Override
  public List<ListenableFuture<TbPair<String, Long>>> removeAllWithVersions(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, List<String> keys) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(attributeScope, "attributeScope");
    Objects.requireNonNull(keys, "keys");
    // Validate every key before launching any delete task, so a blank key never leaves a partial
    // delete behind (fail-fast before any side effect).
    List<String> validatedKeys = new ArrayList<>(keys.size());
    for (String rawKey : keys) {
      validatedKeys.add(requireKey(rawKey));
    }
    List<ListenableFuture<TbPair<String, Long>>> futures = new ArrayList<>(validatedKeys.size());
    for (String key : validatedKeys) {
      futures.add(
          submitReadTask(
              () -> {
                deleteIdentity(tenantId, entityId, attributeScope, key);
                // null version: IoTDB has no sequence (see class javadoc). The ThingsBoard service
                // skips the EDQS attribute-delete notification when the version is null.
                return TbPair.of(key, (Long) null);
              }));
    }
    return futures;
  }

  private void deleteIdentity(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String key)
      throws Exception {
    // Lock ordering: entity read lock (outer) then per-identity lock (inner), matching save(), so a
    // single-key delete is excluded from a concurrent entity-wide removeAllByEntityId.
    Lock entityReadLock = entityLock(tenantId, entityId).readLock();
    Lock lock = identityLock(tenantId, entityId, attributeScope, key);
    entityReadLock.lock();
    try {
      lock.lock();
      try {
        String sql = buildDeleteSql(tenantId, entityId, attributeScope, key);
        try (ITableSession session = tableSessionPool.getSession()) {
          session.executeNonQueryStatement(sql);
        }
      } finally {
        lock.unlock();
      }
    } finally {
      entityReadLock.unlock();
    }
  }

  // ---- findNextBatch: deferred relational migration helper ----

  @Override
  public List<AttributeKvEntity> findNextBatch(
      UUID entityId, int attributeType, int attributeKey, int batchSize) {
    throw new UnsupportedOperationException(
        "findNextBatch is a relational keyset-pagination migration helper not supported by the "
            + "IoTDB Table Mode backend");
  }

  // ---- key discovery + bulk latest read + removeAllByEntityId ----

  @Override
  public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityIds, "entityIds");
    if (entityIds.isEmpty()) {
      return List.of();
    }
    try {
      return doFindDistinctKeys(buildKeysByEntityIdsSql(tenantId, entityIds, null));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read attribute keys by entity ids", e);
    }
  }

  @Override
  public List<String> findAllKeysByEntityIdsAndScope(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityIds, "entityIds");
    Objects.requireNonNull(scope, "scope");
    if (entityIds.isEmpty()) {
      return List.of();
    }
    try {
      return doFindDistinctKeys(buildKeysByEntityIdsSql(tenantId, entityIds, scope));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read attribute keys by entity ids and scope", e);
    }
  }

  @Override
  public ListenableFuture<List<String>> findAllKeysByEntityIdsAndScopeAsync(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityIds, "entityIds");
    Objects.requireNonNull(scope, "scope");
    if (entityIds.isEmpty()) {
      return Futures.immediateFuture(List.of());
    }
    // [v4.3.1.2] async wrapper: run the same distinct-key read on the bounded IO executor.
    return submitReadTask(
        () -> doFindDistinctKeys(buildKeysByEntityIdsSql(tenantId, entityIds, scope)));
  }

  @Override
  public List<AttributeKvEntry> findLatestByEntityIdsAndScope(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityIds, "entityIds");
    Objects.requireNonNull(scope, "scope");
    if (entityIds.isEmpty()) {
      return List.of();
    }
    // [v4.3.1.2] bulk latest read: each identity holds exactly one current row (delete-then-insert
    // convergence), so a single SELECT over the entity OR-set in this scope already returns the
    // latest value per (entity, key). Best-effort (unlocked) like find(keys)/findAll; see README.
    try {
      return doFindKeyed(buildLatestByEntityIdsSql(tenantId, entityIds, scope));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to read latest attributes by entity ids and scope", e);
    }
  }

  @Override
  public ListenableFuture<List<AttributeKvEntry>> findLatestByEntityIdsAndScopeAsync(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityIds, "entityIds");
    Objects.requireNonNull(scope, "scope");
    if (entityIds.isEmpty()) {
      return Futures.immediateFuture(List.of());
    }
    // [v4.3.1.2] async wrapper: run the same bulk latest read on the bounded IO executor.
    return submitReadTask(() -> doFindKeyed(buildLatestByEntityIdsSql(tenantId, entityIds, scope)));
  }

  @Override
  public List<String> findAllKeysByDeviceProfileId(
      TenantId tenantId, DeviceProfileId deviceProfileId) {
    Objects.requireNonNull(tenantId, "tenantId");
    // A null deviceProfileId is the "all profiles" path: return the tenant-wide distinct keys,
    // which entity_attributes CAN derive (mirrors the latest DAO).
    if (deviceProfileId == null) {
      try {
        return doFindDistinctKeys(buildKeysByTenantSql(tenantId));
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException("Failed to read attribute keys by tenant", e);
      }
    }
    // Non-null profile lookup returns an empty list, matching the official non-relational backend:
    // CassandraBaseTimeseriesLatestDao.findAllKeysByDeviceProfileId also returns
    // Collections.emptyList(), because a NoSQL / time-series store cannot do the cross-device-table
    // profile-dimension join. The sole upstream caller is DeviceProfileController
    // GET /api/deviceProfile/devices/keys/attributes (getAttributesKeys, @PreAuthorize
    // TENANT_ADMIN) -- a config-time UI key-enumeration endpoint that tolerates an empty result;
    // failing loud here would 500 under IoTDB while Cassandra returns empty, an avoidable backend
    // inconsistency. entity_attributes has no device_profile_id tag and the module has no
    // device -> profile lookup; a real cross-DB implementation is a Phase-2 optional enhancement,
    // not Phase-1.
    return Collections.emptyList();
  }

  @Override
  public List<Pair<AttributeScope, String>> removeAllByEntityId(
      TenantId tenantId, EntityId entityId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityId, "entityId");
    // Best-effort select-then-delete: IoTDB has no DELETE ... RETURNING, so the deleted (scope,
    // key)
    // pairs are gathered by a SELECT DISTINCT first, then a tag-only DELETE removes every attribute
    // row for the entity across all scopes. The entity WRITE lock serializes this whole select+
    // delete against any concurrent same-entity save/deleteIdentity (which hold the entity READ
    // lock), so a concurrent save cannot re-INSERT a key between the select and the delete and
    // leave
    // a row behind after a "remove all". A key inserted by another node (cluster mode) may still be
    // deleted but not reported (documented Phase-1 window; see class javadoc).
    Lock entityWriteLock = entityLock(tenantId, entityId).writeLock();
    entityWriteLock.lock();
    try {
      List<Pair<AttributeScope, String>> removed = new ArrayList<>();
      String selectSql = buildScopeKeyByEntitySql(tenantId, entityId);
      try (ITableSession session = tableSessionPool.getSession();
          SessionDataSet dataSet = session.executeQueryStatement(selectSql)) {
        SessionDataSet.DataIterator row = dataSet.iterator();
        while (row.next()) {
          AttributeScope scope = AttributeScope.valueOf(row.getString("attribute_scope"));
          removed.add(Pair.of(scope, row.getString("key")));
        }
      }
      String deleteSql = buildDeleteByEntitySql(tenantId, entityId);
      try (ITableSession session = tableSessionPool.getSession()) {
        session.executeNonQueryStatement(deleteSql);
      }
      return removed;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to remove all attributes by entity id", e);
    } finally {
      entityWriteLock.unlock();
    }
  }

  private List<String> doFindDistinctKeys(String sql) throws Exception {
    List<String> keys = new ArrayList<>();
    try (ITableSession session = tableSessionPool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      SessionDataSet.DataIterator row = dataSet.iterator();
      while (row.next()) {
        keys.add(row.getString("key"));
      }
    }
    return keys;
  }

  // ---- SQL builders ----

  private String buildDeleteSql(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String key) {
    return "DELETE FROM "
        + TABLE_NAME
        + " WHERE "
        + identityPredicate(tenantId, entityId, attributeScope, key);
  }

  private String buildFindSql(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String key) {
    return "SELECT "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE "
        + identityPredicate(tenantId, entityId, attributeScope, key);
  }

  private String buildFindKeysSql(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      Collection<String> keys) {
    StringBuilder in = new StringBuilder();
    boolean first = true;
    for (String key : keys) {
      if (!first) {
        in.append(",");
      }
      in.append(sqlString(requireKey(key)));
      first = false;
    }
    return "SELECT key, "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE "
        + scopePredicate(tenantId, entityId, attributeScope)
        + " AND key IN ("
        + in
        + ")";
  }

  private String buildFindAllSql(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope) {
    return "SELECT key, "
        + SELECT_TYPED_COLUMNS
        + " FROM "
        + TABLE_NAME
        + " WHERE "
        + scopePredicate(tenantId, entityId, attributeScope);
  }

  private String buildLatestByEntityIdsSql(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    StringBuilder sql =
        new StringBuilder("SELECT key, ")
            .append(SELECT_TYPED_COLUMNS)
            .append(" FROM ")
            .append(TABLE_NAME)
            .append(" WHERE tenant_id=")
            .append(sqlString(tenantId.getId().toString()))
            .append(" AND attribute_scope=")
            .append(sqlString(scope.name()))
            .append(" AND (");
    appendEntityOrSet(sql, entityIds);
    sql.append(")");
    return sql.toString();
  }

  private String buildKeysByEntityIdsSql(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    StringBuilder sql =
        new StringBuilder("SELECT DISTINCT key FROM ")
            .append(TABLE_NAME)
            .append(" WHERE tenant_id=")
            .append(sqlString(tenantId.getId().toString()));
    if (scope != null) {
      sql.append(" AND attribute_scope=").append(sqlString(scope.name()));
    }
    sql.append(" AND (");
    appendEntityOrSet(sql, entityIds);
    sql.append(") ORDER BY key");
    return sql.toString();
  }

  private void appendEntityOrSet(StringBuilder sql, List<EntityId> entityIds) {
    for (int i = 0; i < entityIds.size(); i++) {
      EntityId entityId = Objects.requireNonNull(entityIds.get(i), "entityId");
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append("(entity_type=")
          .append(sqlString(entityId.getEntityType().name()))
          .append(" AND entity_id=")
          .append(sqlString(entityId.getId().toString()))
          .append(")");
    }
  }

  private String buildKeysByTenantSql(TenantId tenantId) {
    return "SELECT DISTINCT key FROM "
        + TABLE_NAME
        + " WHERE tenant_id="
        + sqlString(tenantId.getId().toString())
        + " ORDER BY key";
  }

  private String buildScopeKeyByEntitySql(TenantId tenantId, EntityId entityId) {
    return "SELECT DISTINCT attribute_scope, key FROM "
        + TABLE_NAME
        + " WHERE "
        + entityPredicate(tenantId, entityId)
        + " ORDER BY attribute_scope, key";
  }

  private String buildDeleteByEntitySql(TenantId tenantId, EntityId entityId) {
    return "DELETE FROM " + TABLE_NAME + " WHERE " + entityPredicate(tenantId, entityId);
  }

  private String identityPredicate(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String key) {
    return scopePredicate(tenantId, entityId, attributeScope) + " AND key=" + sqlString(key);
  }

  private String scopePredicate(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope) {
    return entityPredicate(tenantId, entityId)
        + " AND attribute_scope="
        + sqlString(attributeScope.name());
  }

  // ---- mapping + helpers ----

  private Lock identityLock(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String key) {
    return identityLocks.get(
        tenantId.getId().toString()
            + LOCK_KEY_SEPARATOR
            + entityId.getEntityType().name()
            + LOCK_KEY_SEPARATOR
            + entityId.getId().toString()
            + LOCK_KEY_SEPARATOR
            + attributeScope.name()
            + LOCK_KEY_SEPARATOR
            + key);
  }

  private ReadWriteLock entityLock(TenantId tenantId, EntityId entityId) {
    return entityLocks.get(
        tenantId.getId().toString()
            + LOCK_KEY_SEPARATOR
            + entityId.getEntityType().name()
            + LOCK_KEY_SEPARATOR
            + entityId.getId().toString());
  }

  private static String requireKey(String key) {
    return requireKey(key, "Attribute");
  }

  private static void requireClusterModeAcknowledged(String clusterMode) {
    String mode = clusterMode == null ? null : clusterMode.trim();
    if (mode == null || (!mode.equals("sticky-routing") && !mode.equals("disabled"))) {
      throw new IllegalStateException(
          "iotdb.attributes.cluster_mode must be explicitly set to 'sticky-routing' or 'disabled' "
              + "when the IoTDB Table Mode attribute DAO is active; got '"
              + clusterMode
              + "'. The attribute write path (delete-then-insert under a per-identity in-JVM lock) "
              + "converges only within a single JVM, so cross-node single-writer safety must be "
              + "acknowledged.");
    }
  }

  // ---- bounded IO executor (shared base mechanism; iotdb.attributes.executor.*) ----

  @Override
  public void destroy() {
    if (!destroyed.compareAndSet(false, true)) {
      return;
    }
    shutdownReadExecutor();
  }
}
