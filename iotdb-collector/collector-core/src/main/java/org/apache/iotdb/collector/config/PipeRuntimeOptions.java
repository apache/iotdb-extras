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

import org.apache.iotdb.rpc.RpcUtils;

import java.util.concurrent.TimeUnit;

public class PipeRuntimeOptions extends Options {
  public static final Option<Double> PIPE_ALL_SINK_RATE_LIMIT_BYTES_PER_SECOND =
      new Option<Double>("pipe_all_sinks_rate_limit_bytes_per_second", -1d) {
        @Override
        public void setValue(final String valueString) {
          value = Double.parseDouble(valueString);
        }
      };

  public static final Option<Integer> RATE_LIMITER_HOT_RELOAD_CHECK_INTERVAL_MS =
      new Option<Integer>("rate_limiter_hot_reload_check_interval_ms", 1000) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> MAX_RETRY_TIMES =
      new Option<Integer>("max_retry_times", 5) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> RPC_PORT =
      new Option<Integer>("rpc_port", 6667) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<String> RPC_ADDRESS =
      new Option<String>("rpc_address", "0.0.0.0") {
        @Override
        public void setValue(final String valueString) {
          value = valueString;
        }
      };

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

  public static final Option<Boolean> IS_PIPE_CONNECTOR_RPC_THRIFT_COMPRESSION_ENABLED =
      new Option<Boolean>("is_pipe_connector_rpc_thrift_compression_enabled", false) {
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

  public static final Option<Boolean> PIPE_EVENT_REFERENCE_TRACKING_ENABLED =
      new Option<Boolean>("pipe_event_reference_tracking_enabled", true) {
        @Override
        public void setValue(String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static volatile Option<Long> PIPE_CHECK_MEMORY_ENOUGH_INTERVAL_MS =
      new Option<Long>("pipe_check_memory_enough_interval_ms", 10L) {
        @Override
        public void setValue(final String valueString) {
          value = Long.parseLong(valueString);
        }
      };

  public static final Option<Boolean> PIPE_CONNECTOR_READ_FILE_BUFFER_MEMORY_CONTROL =
      new Option<Boolean>("pipe_connector_read_file_buffer_memory_control", false) {
        @Override
        public void setValue(final String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static final Option<Integer> PIPE_SUBTASK_EXECUTOR_MAX_THREAD_NUM =
      new Option<Integer>(
          "pipe_subtask_executor_max_thread_num",
          Math.max(5, Runtime.getRuntime().availableProcessors() / 2)) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_DATA_STRUCTURE_TABLET_SIZE_IN_BYTES =
      new Option<Integer>("pipe_data_structure_tablet_size_in_bytes", 2097152) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_DATA_STRUCTURE_TABLET_ROW_SIZE =
      new Option<Integer>("pipe_data_structure_tablet_row_size", 2048) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Boolean> PIPE_AIR_GAP_RECEIVER_ENABLED =
      new Option<Boolean>("pipe_air_gap_receiver_enabled", true) {

        @Override
        public void setValue(final String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static final Option<Integer> PIPE_AIR_GAP_RECEIVER_PORT =
      new Option<Integer>("pipe_air_gap_receiver_port", 9780) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Long> PIPE_SUBTASK_EXECUTOR_PENDING_QUEUE_MAX_BLOCKING_TIME_MS =
      new Option<Long>("pipe_subtask_executor_pending_queue_max_blocking_time_ms", 1000L) {
        @Override
        public void setValue(final String valueString) {
          value = Long.parseLong(valueString);
        }
      };

  public static final Option<Boolean> TIMESTAMP_PRECISION_CHECK_ENABLED =
      new Option<Boolean>("timestamp_precision_check_enabled", true) {
        @Override
        public void setValue(final String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static final Option<Integer> DN_CONNECTION_TIMEOUT_IN_MS =
      new Option<Integer>("dn_connection_timeout_in_ms", (int) TimeUnit.SECONDS.toMillis(60)) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Boolean> IS_RPC_THRIFT_COMPRESSION_ENABLED =
      new Option<Boolean>("is_rpc_thrift_compression_enabled", false) {
        @Override
        public void setValue(final String valueString) {
          value = Boolean.parseBoolean(valueString);
        }
      };

  public static final Option<Integer> PIPE_CONNECTOR_REQUEST_SLICE_THRESHOLD_BYTES =
      new Option<Integer>(
          "pipe_connector_request_slice_threshold_bytes",
          (int) (RpcUtils.THRIFT_FRAME_MAX_SIZE * 0.8)) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_CONNECTOR_HANDSHAKE_TIMEOUT_MS =
      new Option<Integer>("pipe_connector_handshake_timeout_ms", 10 * 1000) {
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

  public static final Option<Integer> PIPE_ASYNC_CONNECTOR_SELECTOR_NUMBER =
      new Option<Integer>(
          "pipe_async_connector_selector_number",
          Math.max(4, Runtime.getRuntime().availableProcessors() / 2)) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Integer> PIPE_ASYNC_CONNECTOR_MAX_CLIENT_NUMBER =
      new Option<Integer>(
          "pipe_async_connector_max_client_number",
          Math.max(16, Runtime.getRuntime().availableProcessors() / 2)) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static volatile Option<Integer> DATA_NODE_ID =
      new Option<Integer>("data_node_id", -1) {
        @Override
        public void setValue(final String valueString) {
          value = Integer.parseInt(valueString);
        }
      };

  public static final Option<Long> EXECUTOR_CRON_HEARTBEAT_EVENT_INTERVAL_SECONDS =
      new Option<Long>("executor_cron_heartbeat_event_interval_seconds", 20L) {
        @Override
        public void setValue(final String valueString) {
          value = Long.parseLong(valueString);
        }
      };
}
