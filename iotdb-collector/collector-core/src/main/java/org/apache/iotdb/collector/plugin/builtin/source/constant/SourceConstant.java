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

package org.apache.iotdb.collector.plugin.builtin.source.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SourceConstant {

  public static final String SOURCE_REPORT_TIME_INTERVAL_KEY = "report-time-interval";
  public static final String SOURCE_IS_ALIGNED_KEY = "is-aligned";
  public static final String SOURCE_DEVICE_ID_KEY = "device-id";

  public static final int SOURCE_REPORT_TIME_INTERVAL_DEFAULT_VALUE = 60;
  public static final boolean SOURCE_IS_ALIGNED_DEFAULT_VALUE = false;
  public static final String SOURCE_DEVICE_ID_DEFAULT_VALUE = "root.test";

  public static final String SOURCE_SQL_DIALECT_KEY = "sql-dialect";
  public static final String SOURCE_SQL_DIALECT_DEFAULT_VALUE = "tree";
  public static final Set<String> SOURCE_SQL_DIALECT_VALUE_SET =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("tree", "table")));

  public static final Set<String> BOOLEAN_SET =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("true", "false")));
}
