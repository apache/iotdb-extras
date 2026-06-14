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
// (commit c37fb509) on 2026-06-12.
package org.thingsboard.server.common.data.id;

import org.thingsboard.server.common.data.EntityType;

import java.util.UUID;

public final class TenantId extends UUIDBased implements EntityId {
  public static final TenantId SYS_TENANT_ID = TenantId.fromUUID(EntityId.NULL_UUID);

  public static TenantId fromUUID(UUID id) {
    return new TenantId(id);
  }

  public TenantId(UUID id) {
    super(id);
  }

  public boolean isSysTenantId() {
    return this.equals(SYS_TENANT_ID);
  }

  @Override
  public EntityType getEntityType() {
    return EntityType.TENANT;
  }
}
