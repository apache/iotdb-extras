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

package org.apache.iotdb.collector.config;

import java.io.File;

public class TaskRuntimeOptions extends Options {

  //////////////////////////// Param from rest request ////////////////////////////

  public static final String TASK_SOURCE_PARALLELISM_NUM_KEY = "source-parallelism-num";
  public static final Integer TASK_SOURCE_PARALLELISM_NUM_DEFAULT_VALUE = 4;

  public static final String TASK_PROCESSOR_PARALLELISM_NUM_KEY = "processor-parallelism-num";
  public static final Integer TASK_PROCESSOR_PARALLELISM_NUM_DEFAULT_VALUE = 4;

  public static final String TASK_SINK_PARALLELISM_NUM_KEY = "sink-parallelism-num";
  public static final Integer TASK_SINK_PARALLELISM_NUM_DEFAULT_VALUE = 4;

  public static final String TASK_PROCESSOR_RING_BUFFER_SIZE_KEY = "processor-ring-buffer-size";
  public static final Integer TASK_PROCESSOR_RING_BUFFER_SIZE_DEFAULT_VALUE = 1024;

  public static final String TASK_SINK_RING_BUFFER_SIZE_KEY = "sink-ring-buffer-size";
  public static final Integer TASK_SINK_RING_BUFFER_SIZE_DEFAULT_VALUE = 1024;

  //////////////////////////// Param from application.properties ////////////////////////////

  public static final Option<String> TASK_DATABASE_FILE_PATH =
      new Option<String>(
          "task_database_file_path",
          "system" + File.separator + "database" + File.separator + "task.db") {
        @Override
        public void setValue(final String valueString) {
          value = addHomeDir(valueString);
        }
      };

  public static final Option<Long> EXECUTOR_CRON_HEARTBEAT_EVENT_INTERVAL_SECONDS =
      new Option<Long>("executor_cron_heartbeat_event_interval_seconds", 5L) {
        @Override
        public void setValue(final String valueString) {
          value = Long.parseLong(valueString);
        }
      };

  public static final Option<Integer> TASK_PROGRESS_REPORT_INTERVAL =
      new Option<Integer>("task_progress_report_interval", 60) {
        @Override
        public void setValue(String valueString) {
          value = Integer.parseInt(valueString);
        }
      };
}
