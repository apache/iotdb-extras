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

  public static final Option<Integer> TASK_SOURCE_PARALLELISM_NUM =
      new Option<Integer>("task_source_parallelism_num", 4) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> TASK_PROCESS_PARALLELISM_NUM =
      new Option<Integer>("task_process_parallelism_num", 4) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> TASK_SINK_PARALLELISM_NUM =
      new Option<Integer>("task_sink_parallelism_num", 4) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> TASK_PROCESSOR_RING_BUFFER_SIZE =
      new Option<Integer>("task_processor_ring_buffer_size", 1024) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> TASK_SINK_RING_BUFFER_SIZE =
      new Option<Integer>("task_sink_ring_buffer_size", 1024) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<String> TASK_DATABASE_FILE_PATH =
      new Option<String>(
          "task_database_file_path",
          "system" + File.separator + "database" + File.separator + "task.db") {
        @Override
        public void setValue(final String valueString) {
          value = addHomeDir(valueString);
        }
      };
}
