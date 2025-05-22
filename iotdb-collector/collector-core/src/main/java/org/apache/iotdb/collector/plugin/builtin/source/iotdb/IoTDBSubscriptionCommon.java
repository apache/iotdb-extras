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

import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.session.subscription.consumer.base.AbstractSubscriptionConsumerBuilder;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_IS_ALIGNED_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_IS_ALIGNED_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_SQL_DIALECT_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_SQL_DIALECT_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_SQL_DIALECT_VALUE_SET;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_MIN_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_CONSUMER_ID_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_MIN_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_GROUP_ID_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_MIN_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_HOST_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_HOST_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_PORT_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_PORT_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_TOPIC_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_TOPIC_KEY;

public class IoTDBSubscriptionCommon {

  private String host;
  private Integer port;
  private String consumerId;
  private String groupId;
  private Long heartbeatIntervalMs;
  private Long endpointsSyncIntervalMs;
  private Integer thriftMaxFrameSize;
  private Integer maxPollParallelism;

  private String topic;

  private final BlockingQueue<Event> eventQueue = new LinkedBlockingQueue<>();
  private static final Integer EVENT_QUEUE_CAPACITY = 1000;
  private static final Long EVENT_QUEUE_PAUSE_INTERVAL_MS = 100_000_000L;

  // validate common parameters
  public void validate(final PipeParameterValidator validator) {
    CollectorParameters.validateStringRequiredParam(validator, IOTDB_SUBSCRIPTION_SOURCE_TOPIC_KEY);
    CollectorParameters.validateStringRequiredParam(
        validator, IOTDB_SUBSCRIPTION_SOURCE_GROUP_ID_KEY);
    CollectorParameters.validateStringRequiredParam(
        validator, IOTDB_SUBSCRIPTION_SOURCE_CONSUMER_ID_KEY);

    CollectorParameters.validateBooleanParam(
        validator, SOURCE_IS_ALIGNED_KEY, SOURCE_IS_ALIGNED_DEFAULT_VALUE);

    CollectorParameters.validateIntegerParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_PORT_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_PORT_DEFAULT_VALUE,
        value -> value > 0);
    CollectorParameters.validateIntegerParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_DEFAULT_VALUE,
        value -> value > 0);
    CollectorParameters.validateIntegerParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_DEFAULT_VALUE,
        value -> value > 0);

    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_DEFAULT_VALUE,
        value -> value >= IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_MIN_VALUE);
    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_DEFAULT_VALUE,
        value -> value >= IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_MIN_VALUE);
    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_DEFAULT_VALUE,
        value -> value >= IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_MIN_VALUE);

    CollectorParameters.validateSetParam(
        validator,
        SOURCE_SQL_DIALECT_KEY,
        SOURCE_SQL_DIALECT_VALUE_SET,
        SOURCE_SQL_DIALECT_DEFAULT_VALUE);
  }

  // customize common parameters
  public void customize(
      final PipeParameters pipeParameters, final PipeSourceRuntimeConfiguration configuration) {
    host =
        pipeParameters.getStringOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_HOST_KEY, IOTDB_SUBSCRIPTION_SOURCE_HOST_DEFAULT_VALUE);
    port =
        pipeParameters.getIntOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_PORT_KEY, IOTDB_SUBSCRIPTION_SOURCE_PORT_DEFAULT_VALUE);
    topic =
        pipeParameters.getStringOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_TOPIC_KEY, IOTDB_SUBSCRIPTION_SOURCE_TOPIC_DEFAULT_VALUE);
    consumerId = pipeParameters.getString(IOTDB_SUBSCRIPTION_SOURCE_CONSUMER_ID_KEY);
    groupId = pipeParameters.getString(IOTDB_SUBSCRIPTION_SOURCE_GROUP_ID_KEY);
    heartbeatIntervalMs =
        pipeParameters.getLongOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_HEARTBEAT_INTERVAL_MS_DEFAULT_VALUE);
    endpointsSyncIntervalMs =
        pipeParameters.getLongOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_ENDPOINT_SYNC_INTERVAL_MS_DEFAULT_VALUE);
    thriftMaxFrameSize =
        pipeParameters.getIntOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_THRIFT_MAX_FRAME_SIZE_DEFAULT_VALUE);
    maxPollParallelism =
        pipeParameters.getIntOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_MAX_POLL_PARALLELISM_DEFAULT_VALUE);
  }

  // common consumer builder
  public AbstractSubscriptionConsumerBuilder getSubscriptionConsumerBuilder() {
    return new AbstractSubscriptionConsumerBuilder()
        .host(host)
        .port(port)
        .consumerId(consumerId)
        .consumerGroupId(groupId)
        .heartbeatIntervalMs(heartbeatIntervalMs)
        .endpointsSyncIntervalMs(endpointsSyncIntervalMs)
        .thriftMaxFrameSize(thriftMaxFrameSize)
        .maxPollParallelism(maxPollParallelism);
  }

  public Event take() throws InterruptedException {
    return eventQueue.take();
  }

  public void put(final Event event) throws InterruptedException {
    eventQueue.put(event);
  }

  public void checkIfNeedPause() {
    while (eventQueue.size() >= EVENT_QUEUE_CAPACITY) {
      LockSupport.parkNanos(EVENT_QUEUE_PAUSE_INTERVAL_MS);
    }
  }

  public String getTopic() {
    return topic;
  }
}
