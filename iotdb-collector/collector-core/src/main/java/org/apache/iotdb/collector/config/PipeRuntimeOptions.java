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

public class PipeRuntimeOptions extends Options {

  public static final Option<Integer> PIPE_CONNECTOR_READ_FILE_BUFFER_SIZE =
      new Option<Integer>("pipe_connector_read_file_buffer_size", 8388608) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_CONNECTOR_TRANSFER_TIMEOUT_MS =
      new Option<Integer>("pipe_connector_transfer_timeout_ms", 15 * 60 * 1000) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> THRIFT_FRAME_MAX_SIZE =
      new Option<Integer>("thrift_frame_max_size", 546870912) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Boolean> PIPE_CONNECTOR_RPC_THRIFT_COMPRESSION_ENABLED =
      new Option<Boolean>("pipe_connector_rpc_thrift_compression_enabled", false) {
        @Override
        public void setValue(final String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static final Option<String> TIMESTAMP_PRECISION =
      new Option<String>("timestamp_precision", "ms") {
        @Override
        public void setValue(final String valueString) {
          value = valueString;
        }
      };

  public static final Option<String> CLUSTER_ID =
      new Option<String>("cluster_id", "") {
        @Override
        public void setValue(final String valueString) {
          value = valueString;
        }
      };

  public static final Option<Float> PIPE_LEADER_CACHE_MEMORY_USAGE_PERCENTAGE =
      new Option<Float>("pipe_leader_cache_memory_usage_percentage", 0.1f) {
        @Override
        public void setValue(String valueString) {
          value = Float.parseFloat(valueString);
        }
      };

  public static final Option<Integer> PIPE_CONNECTOR_HANDSHAKE_TIMEOUT_MS =
      new Option<Integer>("pipe_connector_handshake_timeout_ms", 10 * 1000) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> DATA_NODE_ID =
      new Option<Integer>("data_node_id", -1) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Boolean> PIPE_MEMORY_MANAGEMENT_ENABLED =
      new Option<Boolean>("pipe_memory_management_enabled", true) {
        @Override
        public void setValue(String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static final Option<Integer> PIPE_MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES =
      new Option<Integer>("pipe_memory_allocate_min_size_in_bytes", 32) {
        @Override
        public void setValue(String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Double> PIPE_TOTAL_FLOATING_MEMORY_PROPORTION =
      new Option<Double>("pipe_total_floating_memory_proportion", 0.2) {
        @Override
        public void setValue(String valueString) {
          value = Double.parseDouble(valueString);
        }
      };

  public static final Option<Integer> PIPE_MAX_ALLOWED_EVENT_COUNT_IN_TABLET_BATCH =
      new Option<Integer>("pipe_max_allowed_event_count_in_tablet_batch", 100) {
        @Override
        public void setValue(String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_MEMORY_ALLOCATE_MAX_RETRIES =
      new Option<Integer>("pipe_memory_allocate_max_retries", 10) {
        @Override
        public void setValue(String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_MEMORY_ALLOCATE_RETRY_INTERVAL_MS =
      new Option<Integer>("pipe_memory_allocate_retry_interval_ms", 50) {
        @Override
        public void setValue(String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Double>
      PIPE_DATA_STRUCTURE_TABLET_MEMORY_BLOCK_ALLOCATION_REJECT_THRESHOLD =
          new Option<Double>(
              "pipe_data_structure_tablet_memory_block_allocation_reject_threshold", 0.4) {
            @Override
            public void setValue(String valueString) {
              value = Double.parseDouble(valueString);
            }
          };

  public static final Option<Double>
      PIPE_DATA_STRUCTURE_TS_FILE_MEMORY_BLOCK_ALLOCATION_REJECT_THRESHOLD =
          new Option<Double>(
              "pipe_data_structure_ts_file_memory_block_allocation_reject_threshold", 0.4) {
            @Override
            public void setValue(String valueString) {
              value = Double.parseDouble(valueString);
            }
          };
}
