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

package org.thingsboard.server.dao.timeseries;

import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;

import java.util.List;
import java.util.Optional;

/**
 * Compile-only Strategy-F surface for ThingsBoard's {@code TimeseriesLatestDao} SPI. Pinned to
 * ThingsBoard {@code v4.3.1.2} (tag commit {@code c37fb509}); re-verified against upstream on
 * 2026-06-20. The {@code findLatestByEntityIds} / {@code findLatestByEntityIdsAsync} pair is new in
 * v4.3.1.2 relative to v4.3.1.1. The method shapes the DAO depends on are pinned by {@code
 * StrategyFContractTest}.
 */
public interface TimeseriesLatestDao {

  ListenableFuture<Optional<TsKvEntry>> findLatestOpt(
      TenantId tenantId, EntityId entityId, String key);

  ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key);

  ListenableFuture<List<TsKvEntry>> findAllLatest(TenantId tenantId, EntityId entityId);

  ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry);

  ListenableFuture<TsKvLatestRemovingResult> removeLatest(
      TenantId tenantId, EntityId entityId, DeleteTsKvQuery query);

  List<String> findAllKeysByDeviceProfileId(TenantId tenantId, DeviceProfileId deviceProfileId);

  List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds);

  ListenableFuture<List<String>> findAllKeysByEntityIdsAsync(
      TenantId tenantId, List<EntityId> entityIds);

  // New in ThingsBoard v4.3.1.2 (batch latest read).
  List<TsKvEntry> findLatestByEntityIds(TenantId tenantId, List<EntityId> entityIds);

  ListenableFuture<List<TsKvEntry>> findLatestByEntityIdsAsync(
      TenantId tenantId, List<EntityId> entityIds);
}
