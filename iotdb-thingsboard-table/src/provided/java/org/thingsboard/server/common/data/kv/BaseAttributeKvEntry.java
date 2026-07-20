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
package org.thingsboard.server.common.data.kv;

import java.util.Objects;
import java.util.Optional;

/**
 * Compile-only ThingsBoard surface stub (Strategy F): mirrors {@code
 * org.thingsboard.server.common.data.kv.BaseAttributeKvEntry}. Wraps a {@link KvEntry} together
 * with its {@code lastUpdateTs} and optional {@code version}, delegating all {@link KvEntry} getters
 * to the wrapped entry. Excluded from the built jar.
 */
public class BaseAttributeKvEntry implements AttributeKvEntry {

  private final long lastUpdateTs;
  private final KvEntry kv;
  private final Long version;

  public BaseAttributeKvEntry(KvEntry kv, long lastUpdateTs) {
    this.kv = kv;
    this.lastUpdateTs = lastUpdateTs;
    this.version = null;
  }

  // Real TB also exposes the (long, KvEntry) argument order; mirror it for stub fidelity.
  public BaseAttributeKvEntry(long lastUpdateTs, KvEntry kv) {
    this(kv, lastUpdateTs);
  }

  public BaseAttributeKvEntry(KvEntry kv, long lastUpdateTs, Long version) {
    this.kv = kv;
    this.lastUpdateTs = lastUpdateTs;
    this.version = version;
  }

  @Override
  public long getLastUpdateTs() {
    return lastUpdateTs;
  }

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public String getKey() {
    return kv.getKey();
  }

  @Override
  public DataType getDataType() {
    return kv.getDataType();
  }

  @Override
  public Optional<Boolean> getBooleanValue() {
    return kv.getBooleanValue();
  }

  @Override
  public Optional<Long> getLongValue() {
    return kv.getLongValue();
  }

  @Override
  public Optional<Double> getDoubleValue() {
    return kv.getDoubleValue();
  }

  @Override
  public Optional<String> getStrValue() {
    return kv.getStrValue();
  }

  @Override
  public Optional<String> getJsonValue() {
    return kv.getJsonValue();
  }

  @Override
  public String getValueAsString() {
    return kv.getValueAsString();
  }

  @Override
  public Object getValue() {
    return kv.getValue();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BaseAttributeKvEntry)) {
      return false;
    }
    BaseAttributeKvEntry that = (BaseAttributeKvEntry) o;
    return lastUpdateTs == that.lastUpdateTs
        && Objects.equals(kv, that.kv)
        && Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(lastUpdateTs, kv, version);
  }

  @Override
  public String toString() {
    return "BaseAttributeKvEntry(lastUpdateTs="
        + lastUpdateTs
        + ", kv="
        + kv
        + ", version="
        + version
        + ")";
  }
}
