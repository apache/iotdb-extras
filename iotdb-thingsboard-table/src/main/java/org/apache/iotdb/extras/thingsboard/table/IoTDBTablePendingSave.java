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

import com.google.common.util.concurrent.SettableFuture;
import org.thingsboard.server.common.data.kv.DataType;

import java.util.Objects;

record IoTDBTablePendingSave(
    String tenantId,
    String entityType,
    String entityId,
    String key,
    long ts,
    DataType dataType,
    Object value,
    int dataPointDays,
    SettableFuture<Integer> future) {

  IoTDBTablePendingSave(
      String tenantId,
      String entityType,
      String entityId,
      String key,
      long ts,
      DataType dataType,
      Object value,
      int dataPointDays) {
    this(
        tenantId,
        entityType,
        entityId,
        key,
        ts,
        dataType,
        value,
        dataPointDays,
        SettableFuture.create());
  }

  IoTDBTablePendingSave {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(dataType, "dataType");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(future, "future");
  }

  IoTDBTableSaveIdentity identity() {
    return new IoTDBTableSaveIdentity(tenantId, entityType, entityId, key, ts);
  }
}
