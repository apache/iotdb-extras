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

package org.apache.iotdb.metricscrape;

public class IdentifierUtils {

  private IdentifierUtils() {}

  public static String sanitize(String identifier) {
    if (identifier == null || identifier.trim().isEmpty()) {
      return "unnamed";
    }
    StringBuilder builder = new StringBuilder(identifier.length());
    for (int i = 0; i < identifier.length(); i++) {
      char c = identifier.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_') {
        builder.append(c);
      } else {
        builder.append('_');
      }
    }
    if (builder.length() == 0) {
      return "unnamed";
    }
    if (Character.isDigit(builder.charAt(0))) {
      builder.insert(0, '_');
    }
    return builder.toString();
  }

  public static String quoteSqlIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
