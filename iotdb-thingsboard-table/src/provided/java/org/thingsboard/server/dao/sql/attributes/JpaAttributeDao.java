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

// Compile-only ThingsBoard stub (Strategy F). Verified against ThingsBoard v4.3.1.2
// (commit c37fb509).
package org.thingsboard.server.dao.sql.attributes;

import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.lang3.tuple.Pair;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.util.TbPair;
import org.thingsboard.server.dao.attributes.AttributesDao;
import org.thingsboard.server.dao.model.sql.AttributeKvEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Compile-only ThingsBoard surface stub (Strategy F) for {@code
 * org.thingsboard.server.dao.sql.attributes.JpaAttributeDao}, ThingsBoard's built-in JPA
 * entity-attribute DAO. Excluded from the built jar, so it can never shadow the real class on a
 * deployment classpath.
 *
 * <p><b>Only this class's fully-qualified NAME carries meaning here.</b> {@code
 * AttributesDaoConflictGuard} identifies the one host bean it is authorised to withdraw by matching
 * bean name {@code jpaAttributeDao} against resolved type {@code
 * org.thingsboard.server.dao.sql.attributes.JpaAttributeDao}; without a class of that exact name on
 * the test classpath that branch could not be exercised at all. The method bodies are never
 * executed and the guard never instantiates the bean -- it runs as a {@code
 * BeanFactoryPostProcessor}, before any bean is created.
 *
 * <p>Two divergences from the real class, deliberate and harmless for the above purpose: the real
 * one is a {@code @Component} extending {@code JpaAbstractDaoListeningExecutorService}, and it
 * implements these methods against JPA repositories. This stub declares neither the annotation nor
 * the superclass, because the guard reads a bean definition's type and never the class's
 * annotations or hierarchy.
 *
 * <p>The name itself is not proven by any test in this module: ThingsBoard's dao artifact is not on
 * Maven Central, so the string was taken from ThingsBoard's own source at v4.3.1.2, {@code
 * dao/src/main/java/org/thingsboard/server/dao/sql/attributes/JpaAttributeDao.java:58-61}, read
 * independently twice. If ThingsBoard ever renames or repackages that class, this module's
 * attributes selector fails closed -- the bean becomes UNKNOWN and startup stops with a message
 * naming it -- rather than silently withdrawing the wrong bean.
 */
public class JpaAttributeDao implements AttributesDao {

  private static UnsupportedOperationException notExecutable() {
    return new UnsupportedOperationException(
        "compile-only ThingsBoard stub; the real implementation is supplied by the ThingsBoard "
            + "runtime classpath");
  }

  @Override
  public Optional<AttributeKvEntry> find(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, String attributeKey) {
    throw notExecutable();
  }

  @Override
  public List<AttributeKvEntry> find(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      Collection<String> attributeKey) {
    throw notExecutable();
  }

  @Override
  public List<AttributeKvEntry> findAll(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope) {
    throw notExecutable();
  }

  @Override
  public ListenableFuture<Long> save(
      TenantId tenantId,
      EntityId entityId,
      AttributeScope attributeScope,
      AttributeKvEntry attribute) {
    throw notExecutable();
  }

  @Override
  public List<ListenableFuture<String>> removeAll(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, List<String> keys) {
    throw notExecutable();
  }

  @Override
  public List<ListenableFuture<TbPair<String, Long>>> removeAllWithVersions(
      TenantId tenantId, EntityId entityId, AttributeScope attributeScope, List<String> keys) {
    throw notExecutable();
  }

  @Override
  public List<AttributeKvEntity> findNextBatch(
      UUID entityId, int attributeType, int attributeKey, int batchSize) {
    throw notExecutable();
  }

  @Override
  public List<String> findAllKeysByDeviceProfileId(
      TenantId tenantId, DeviceProfileId deviceProfileId) {
    throw notExecutable();
  }

  @Override
  public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    throw notExecutable();
  }

  @Override
  public List<String> findAllKeysByEntityIdsAndScope(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    throw notExecutable();
  }

  @Override
  public ListenableFuture<List<String>> findAllKeysByEntityIdsAndScopeAsync(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    throw notExecutable();
  }

  @Override
  public List<AttributeKvEntry> findLatestByEntityIdsAndScope(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    throw notExecutable();
  }

  @Override
  public ListenableFuture<List<AttributeKvEntry>> findLatestByEntityIdsAndScopeAsync(
      TenantId tenantId, List<EntityId> entityIds, AttributeScope scope) {
    throw notExecutable();
  }

  @Override
  public List<Pair<AttributeScope, String>> removeAllByEntityId(
      TenantId tenantId, EntityId entityId) {
    throw notExecutable();
  }
}
