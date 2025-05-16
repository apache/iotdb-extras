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

package org.apache.iotdb.collector.plugin.builtin.source.kafka;

import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class KafkaSourceConstant {

  public static final String KAFKA_SOURCE_TOPIC_KEY = "topic";
  public static final String KAFKA_SOURCE_BOOTSTRAP_SERVERS_KEY = "bootstrap.servers";
  public static final String KAFKA_SOURCE_GROUP_ID_KEY = "group.id";
  public static final String KAFKA_SOURCE_KEY_DESERIALIZER_KEY = "key.deserializer";
  public static final String KAFKA_SOURCE_VALUE_DESERIALIZER_KEY = "value.deserializer";
  public static final String KAFKA_SOURCE_AUTO_OFFSET_RESET_KEY = "auto.offset.reset";
  public static final String KAFKA_SOURCE_ENABLE_AUTO_COMMIT_KEY = "enable.auto.commit";
  public static final String KAFKA_SOURCE_SESSION_TIMEOUT_MS_KEY = "session.timeout.ms";
  public static final String KAFKA_SOURCE_MAX_POLL_RECORDS_KEY = "max.poll.records";
  public static final String KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_KEY = "max.poll.interval.ms";
  public static final String KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_KEY =
      "partition.assign.strategy";

  public static final String KAFKA_SOURCE_TOPIC_DEFAULT_VALUE = "my_topic";
  public static final String KAFKA_SOURCE_BOOTSTRAP_SERVERS_DEFAULT_VALUE = "localhost:9092";
  public static final String KAFKA_SOURCE_GROUP_ID_DEFAULT_VALUE = "multi-thread-group";
  public static final String KAFKA_SOURCE_KEY_DESERIALIZER_DEFAULT_VALUE =
      StringDeserializer.class.getName();
  public static final String KAFKA_SOURCE_VALUE_DESERIALIZER_DEFAULT_VALUE =
      StringDeserializer.class.getName();
  public static final String KAFKA_SOURCE_AUTO_OFFSET_RESET_DEFAULT_VALUE = "none";
  public static final String KAFKA_SOURCE_ENABLE_AUTO_COMMIT_DEFAULT_VALUE = "false";
  public static final String KAFKA_SOURCE_SESSION_TIMEOUT_MS_DEFAULT_VALUE = "10000";
  public static final String KAFKA_SOURCE_MAX_POLL_RECORDS_DEFAULT_VALUE = "500";
  public static final String KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_DEFAULT_VALUE = "300000";
  public static final String KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_DEFAULT_VALUE =
      RangeAssignor.class.getName();

  public static final Set<String> AUTO_OFFSET_RESET_SET =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("none", "earliest", "latest")));
  public static final Set<String> BOOLEAN_SET =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("true", "false")));

  private KafkaSourceConstant() {
    throw new IllegalStateException("Utility class");
  }
}
