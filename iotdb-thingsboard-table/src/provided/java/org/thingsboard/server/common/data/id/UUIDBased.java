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

import java.io.Serializable;
import java.util.UUID;

public abstract class UUIDBased implements HasUUID, Serializable {
  private final UUID id;
  private transient int hash;

  protected UUIDBased() {
    this(UUID.randomUUID());
  }

  public UUIDBased(UUID id) {
    this.id = id;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public int hashCode() {
    if (hash == 0) {
      hash = 31 + (id == null ? 0 : id.hashCode());
    }
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    UUIDBased other = (UUIDBased) obj;
    if (id == null) {
      return other.id == null;
    }
    return id.equals(other.id);
  }

  @Override
  public String toString() {
    return String.valueOf(id);
  }
}
