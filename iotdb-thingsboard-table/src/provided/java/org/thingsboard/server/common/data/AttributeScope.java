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
package org.thingsboard.server.common.data;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compile-only ThingsBoard surface stub (Strategy F): mirrors {@code
 * org.thingsboard.server.common.data.AttributeScope} so the IoTDB Table Mode DAO can bind the real
 * {@code AttributesDao} SPI without depending on the upstream ThingsBoard artifact. Excluded from
 * the built jar; replace with the provided-scope upstream dependency once it is resolvable.
 *
 * <p>The IoTDB {@code entity_attributes} table stores the scope by {@link #name()} (e.g. {@code
 * SERVER_SCOPE}), not by {@link #getId()}; the integer id is retained only to match the real enum's
 * shape (the custom {@code valueOf(int)} is part of the verified v4.3.1.2 surface).
 */
public enum AttributeScope {
  CLIENT_SCOPE(1),
  SERVER_SCOPE(2),
  SHARED_SCOPE(3);

  private final int id;

  private static final Map<Integer, AttributeScope> VALUES =
      Arrays.stream(values()).collect(Collectors.toMap(AttributeScope::getId, scope -> scope));

  AttributeScope(int id) {
    this.id = id;
  }

  public int getId() {
    return id;
  }

  public static AttributeScope valueOf(int id) {
    return VALUES.get(id);
  }
}
