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

import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.session.subscription.consumer.AckStrategy;
import org.apache.iotdb.session.subscription.consumer.ConsumeListener;
import org.apache.iotdb.session.subscription.consumer.ConsumeResult;
import org.apache.iotdb.session.subscription.consumer.table.SubscriptionTablePushConsumerBuilder;
import org.apache.iotdb.session.subscription.consumer.tree.SubscriptionTreePushConsumerBuilder;
import org.apache.iotdb.session.subscription.payload.SubscriptionSessionDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.LockSupport;

import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_VALUE_MAP;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_MIN_VALUE;

public abstract class IoTDBSubscriptionPushSource extends PushSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBSubscriptionPushSource.class);

  protected volatile boolean isStarted = true;
  protected Thread workerThread;

  private Long autoPollIntervalMs;
  private Long autoPollTimeoutMs;
  private AckStrategy ackStrategy;

  protected final IoTDBSubscriptionCommon subscription = new IoTDBSubscriptionCommon();

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);
    subscription.validate(validator);

    CollectorParameters.validateSetParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_VALUE_MAP.keySet(),
        IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_DEFAULT_VALUE);

    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_DEFAULT_VALUE,
        value -> value > 0);
    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_DEFAULT_VALUE,
        value -> value >= IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_MIN_VALUE);
  }

  @Override
  public void customize(
      final PipeParameters pipeParameters,
      final PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    super.customize(pipeParameters, pipeSourceRuntimeConfiguration);
    subscription.customize(pipeParameters, pipeSourceRuntimeConfiguration);

    ackStrategy =
        IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_VALUE_MAP.get(
            pipeParameters.getStringOrDefault(
                IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_KEY,
                IOTDB_SUBSCRIPTION_SOURCE_ACK_STRATEGY_DEFAULT_VALUE));

    autoPollIntervalMs =
        pipeParameters.getLongOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_INTERVAL_MS_DEFAULT_VALUE);
    autoPollTimeoutMs =
        pipeParameters.getLongOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_POLL_TIMEOUT_MS_DEFAULT_VALUE);
  }

  @Override
  public void start() throws Exception {
    if (workerThread == null || !workerThread.isAlive()) {
      isStarted = true;

      workerThread = new Thread(this::doWork);
      workerThread.setName(getPushConsumerThreadName());
      workerThread.start();
    }
  }

  protected abstract void doWork();

  protected abstract String getPushConsumerThreadName();

  protected ConsumeListener getConsumeListener() {
    return message -> {
      for (final SubscriptionSessionDataSet dataSet : message.getSessionDataSetsHandler()) {
        try {
          markPausePosition();

          subscription.put(new PipeRawTabletInsertionEvent(dataSet.getTablet(), isAligned));
        } catch (final InterruptedException e) {
          LOGGER.warn("{} thread interrupted", getPushConsumerThreadName(), e);
          Thread.currentThread().interrupt();

          return ConsumeResult.FAILURE;
        }
      }

      while (isStarted && !Thread.currentThread().isInterrupted()) {
        LockSupport.park();
      }

      return ConsumeResult.SUCCESS;
    };
  }

  protected SubscriptionTreePushConsumerBuilder getSubscriptionTreePushConsumerBuilder() {
    return new SubscriptionTreePushConsumerBuilder()
        .host(subscription.getHost())
        .port(subscription.getPort())
        .consumerId(subscription.getConsumerId() + instanceIndex)
        .consumerGroupId(subscription.getGroupId())
        .heartbeatIntervalMs(subscription.getHeartbeatIntervalMs())
        .endpointsSyncIntervalMs(subscription.getEndpointsSyncIntervalMs())
        .thriftMaxFrameSize(subscription.getThriftMaxFrameSize())
        .maxPollParallelism(subscription.getMaxPollParallelism())
        .consumeListener(getConsumeListener())
        .ackStrategy(ackStrategy)
        .autoPollIntervalMs(autoPollIntervalMs)
        .autoPollTimeoutMs(autoPollTimeoutMs);
  }

  protected SubscriptionTablePushConsumerBuilder getSubscriptionTablePushConsumer() {
    return new SubscriptionTablePushConsumerBuilder()
        .host(subscription.getHost())
        .port(subscription.getPort())
        .consumerId(subscription.getConsumerId() + instanceIndex)
        .consumerGroupId(subscription.getGroupId())
        .heartbeatIntervalMs(subscription.getHeartbeatIntervalMs())
        .endpointsSyncIntervalMs(subscription.getEndpointsSyncIntervalMs())
        .thriftMaxFrameSize(subscription.getThriftMaxFrameSize())
        .maxPollParallelism(subscription.getMaxPollParallelism())
        .consumeListener(getConsumeListener())
        .ackStrategy(ackStrategy)
        .autoPollIntervalMs(autoPollIntervalMs)
        .autoPollTimeoutMs(autoPollTimeoutMs);
  }

  @Override
  public void close() throws Exception {
    isStarted = false;
    if (workerThread != null) {
      workerThread.interrupt();
      try {
        workerThread.join(1000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      workerThread = null;
    }
  }
}
