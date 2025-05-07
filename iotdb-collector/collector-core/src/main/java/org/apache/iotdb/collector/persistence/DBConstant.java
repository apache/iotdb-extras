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

package org.apache.iotdb.collector.persistence;

import org.apache.iotdb.collector.config.PluginRuntimeOptions;
import org.apache.iotdb.collector.config.TaskRuntimeOptions;

public class DBConstant {

  public static final String CREATE_PLUGIN_TABLE_SQL =
      "CREATE TABLE IF NOT EXISTS plugin\n"
          + "(\n"
          + "    plugin_name TEXT PRIMARY KEY,\n"
          + "    class_name  TEXT NOT NULL,\n"
          + "    jar_name    TEXT NOT NULL,\n"
          + "    jar_md5    TEXT NOT NULL,\n"
          + "    create_time TEXT NOT NULL\n"
          + ");";
  public static final String CREATE_TASK_TABLE_SQL =
      "CREATE TABLE IF NOT EXISTS task\n"
          + "(\n"
          + "    task_id             TEXT PRIMARY KEY,\n"
          + "    task_state          INT  NOT NULL,\n"
          + "    source_attribute    BLOB NOT NULL,\n"
          + "    processor_attribute BLOB NOT NULL,\n"
          + "    sink_attribute      BLOB NOT NULL,\n"
          + "    task_progress       BLOB NOT NULL,\n"
          + "    create_time         TEXT NOT NULL\n"
          + ");";

  public static final String PLUGIN_DATABASE_FILE_PATH = "system/database/plugin.db";
  public static final String TASK_DATABASE_FILE_PATH = "system/database/task.db";

  public static final String PLUGIN_DATABASE_URL =
      "jdbc:sqlite:" + PluginRuntimeOptions.PLUGIN_DATABASE_FILE_PATH.value();
  public static final String TASK_DATABASE_URL =
      "jdbc:sqlite:" + TaskRuntimeOptions.TASK_DATABASE_FILE_PATH.value();
}
