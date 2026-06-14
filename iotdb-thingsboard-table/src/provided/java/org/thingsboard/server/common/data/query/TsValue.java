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
package org.thingsboard.server.common.data.query;

public class TsValue {
  public static final TsValue EMPTY = new TsValue(0, "");

  private final long ts;
  private final String value;
  private final Long count;

  public TsValue(long ts, String value) {
    this(ts, value, null);
  }

  public TsValue(long ts, String value, Long count) {
    this.ts = ts;
    this.value = value;
    this.count = count;
  }

  public long getTs() {
    return ts;
  }

  public String getValue() {
    return value;
  }

  public Long getCount() {
    return count;
  }
}
