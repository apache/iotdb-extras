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
package org.thingsboard.server.common.data.kv;

import java.util.Optional;

public class BasicTsKvEntry implements TsKvEntry {
  private static final int MAX_CHARS_PER_DATA_POINT = 512;

  protected final long ts;
  private final KvEntry kv;
  private final Long version;

  public BasicTsKvEntry(long ts, KvEntry kv) {
    this.ts = ts;
    this.kv = kv;
    this.version = null;
  }

  public BasicTsKvEntry(long ts, KvEntry kv, Long version) {
    this.ts = ts;
    this.kv = kv;
    this.version = version;
  }

  @Override
  public long getTs() {
    return ts;
  }

  public KvEntry getKv() {
    return kv;
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
  public Optional<String> getStrValue() {
    return kv.getStrValue();
  }

  @Override
  public Optional<Long> getLongValue() {
    return kv.getLongValue();
  }

  @Override
  public Optional<Boolean> getBooleanValue() {
    return kv.getBooleanValue();
  }

  @Override
  public Optional<Double> getDoubleValue() {
    return kv.getDoubleValue();
  }

  @Override
  public Optional<String> getJsonValue() {
    return kv.getJsonValue();
  }

  @Override
  public Object getValue() {
    return kv.getValue();
  }

  @Override
  public String getValueAsString() {
    return kv.getValueAsString();
  }

  @Override
  public int getDataPoints() {
    int length;
    switch (getDataType()) {
      case STRING:
        length = getStrValue().orElse("").length();
        break;
      case JSON:
        length = getJsonValue().orElse("").length();
        break;
      default:
        return 1;
    }
    return Math.max(1, (length + MAX_CHARS_PER_DATA_POINT - 1) / MAX_CHARS_PER_DATA_POINT);
  }
}
