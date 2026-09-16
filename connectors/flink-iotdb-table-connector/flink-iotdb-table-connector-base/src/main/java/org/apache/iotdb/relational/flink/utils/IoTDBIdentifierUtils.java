/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.relational.flink.utils;

/** Utilities for rendering IoTDB table-model identifiers in SQL. */
public final class IoTDBIdentifierUtils {

  private IoTDBIdentifierUtils() {}

  /**
   * Quotes a resolved logical identifier using IoTDB's double-quote syntax.
   *
   * <p>The input is treated as a logical name, not as SQL text. Backticks are preserved as part of
   * the identifier and double quotes are escaped by doubling.
   */
  public static String quoteIdentifier(String identifier) {
    if (identifier == null) {
      throw new IllegalArgumentException("Identifier must not be null.");
    }
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
