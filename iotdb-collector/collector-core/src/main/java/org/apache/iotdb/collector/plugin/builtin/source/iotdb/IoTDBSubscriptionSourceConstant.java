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

package org.apache.iotdb.collector.plugin.builtin.source.iotdb;

import org.apache.iotdb.session.subscription.consumer.AckStrategy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class IoTDBSubscriptionSourceConstant {

  public static final String IOTDB_SUBSCRIPTION_SOURCE_TOPIC_KEY = "topic";
  public static final String IOTDB_SUBSCRIPTION_SOURCE_TOPIC_DEFAULT_VALUE = "root_all";

  public static final String IOTDB_SUBSCRIPTION_SOURCE_HOST_KEY = "host";
  public static final String IOTDB_SUBSCRIPTION_SOURCE_HOST_DEFAULT_VALUE = "localhost";

  public static final String IOTDB_SUBSCRIPTION_SOURCE_PORT_KEY = "port";
  public static final int IOTDB_SUBSCRIPTION_SOURCE_PORT_DEFAULT_VALUE = 6667;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_CONSUMER_ID_KEY = "consumer-id";

  public static final String IOTDB_SUBSCRIPTION_SOURCE_GROUP_ID_KEY = "group-id";

  public static final String IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_KEY =
      "heartbeat-interval-ms";
  public static final long IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_DEFAULT_VALUE = 30_000L;
  public static final long IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_MIN_VALUE = 1_000L;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_KEY =
      "endpoint-sync-interval-ms";
  public static final long IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_DEFAULT_VALUE =
      120_000L;
  public static final long IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_MIN_VALUE = 5_000L;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_KEY =
      "thrift-max-frame-size";
  public static final int IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_DEFAULT_VALUE = 67108864;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_KEY =
      "max-poll-parallelism";
  public static final int IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_DEFAULT_VALUE = 1;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY = "auto-commit";
  public static final boolean IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE = true;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY =
      "auto-commit-interval-ms";
  public static final long IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE = 5_000L;
  public static final long IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_MIN_VALUE = 500L;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_KEY = "ack-strategy";
  public static final String IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_BEFORE_VALUE = "before";
  public static final String IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_AFTER_VALUE = "after";
  public static final String IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_DEFAULT_VALUE = "after";
  public static final Map<String, AckStrategy> IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_VALUE_MAP =
      Collections.unmodifiableMap(
          new HashMap<String, AckStrategy>() {
            {
              put(IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_BEFORE_VALUE, AckStrategy.BEFORE_CONSUME);
              put(IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_AFTER_VALUE, AckStrategy.AFTER_CONSUME);
            }
          });

  public static final String IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_KEY =
      "auto-poll-interval-ms";
  public static final long IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_DEFAULT_VALUE = 100L;

  public static final String IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_KEY =
      "auto-poll-timeout-ms";
  public static final long IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_DEFAULT_VALUE = 10_000L;
  public static final long IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_MIN_VALUE = 1_000L;

  private IoTDBSubscriptionSourceConstant() {
    throw new IllegalStateException("Utility class");
  }
}
