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
package org.thingsboard.server.dao.model.sql;

/**
 * Compile-only ThingsBoard surface stub (Strategy F): a deliberately empty placeholder for {@code
 * org.thingsboard.server.dao.model.sql.AttributeKvEntity}.
 *
 * <p>The upstream type is a relational JPA entity ({@code @Entity}, {@code @EmbeddedId},
 * {@code jakarta.persistence}) used only by the keyset-pagination migration helper {@code
 * AttributesDao.findNextBatch}. The IoTDB Table Mode backend documents that method as deferred (it
 * throws {@code UnsupportedOperationException}), so this stub
 * carries none of the ORM fields or annotations — it exists purely so the {@code AttributesDao}
 * interface signature resolves at compile time. Excluded from the built jar; the IoTDB DAO never
 * constructs or returns an instance.
 */
public final class AttributeKvEntity {
  private AttributeKvEntity() {}
}
