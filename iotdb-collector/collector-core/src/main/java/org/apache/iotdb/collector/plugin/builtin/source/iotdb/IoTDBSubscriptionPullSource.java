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

import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.session.subscription.consumer.table.SubscriptionTablePullConsumerBuilder;
import org.apache.iotdb.session.subscription.consumer.tree.SubscriptionTreePullConsumerBuilder;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessage;
import org.apache.iotdb.session.subscription.payload.SubscriptionSessionDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_MIN_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY;

public abstract class IoTDBSubscriptionPullSource extends PullSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBSubscriptionPullSource.class);

  protected static final Long POLL_TIMEOUT_MS = 10_000L;

  protected final IoTDBSubscriptionCommon subscription = new IoTDBSubscriptionCommon();

  private Boolean autoCommit;
  private Long autoCommitIntervalMs;

  protected volatile boolean isStarted;
  protected Thread workerThread;

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);
    subscription.validate(validator);

    CollectorParameters.validateBooleanParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE);

    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE,
        value -> value >= IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_MIN_VALUE);
  }

  @Override
  public void customize(
      PipeParameters pipeParameters, PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    super.customize(pipeParameters, pipeSourceRuntimeConfiguration);
    subscription.customize(pipeParameters, pipeSourceRuntimeConfiguration);

    autoCommit =
        pipeParameters.getBooleanOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE);
    autoCommitIntervalMs =
        pipeParameters.getLongOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE);
  }

  @Override
  public void start() throws Exception {
    initPullConsumer();

    if (workerThread == null) {
      isStarted = true;

      workerThread = new Thread(this::doWork);
      workerThread.setName(getPullConsumerThreadName());
      workerThread.start();
    }
  }

  protected abstract void initPullConsumer();

  protected abstract String getPullConsumerThreadName();

  private void doWork() {
    while (isStarted && !Thread.currentThread().isInterrupted()) {
      final List<SubscriptionMessage> messages = poll();

      for (final SubscriptionMessage message : messages) {
        for (final SubscriptionSessionDataSet dataSet : message.getSessionDataSetsHandler()) {
          try {
            subscription.put(new PipeRawTabletInsertionEvent(dataSet.getTablet(), isAligned));
          } catch (final InterruptedException e) {
            LOGGER.warn("{} thread interrupted", getPullConsumerThreadName(), e);
            Thread.currentThread().interrupt();
          }
        }
      }

      if (!autoCommit) {
        commitAsync(messages);
      }
    }
  }

  protected abstract List<SubscriptionMessage> poll();

  protected abstract void commitAsync(List<SubscriptionMessage> messages);

  @Override
  public Event supply() throws InterruptedException {
    return subscription.take();
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

  protected SubscriptionTreePullConsumerBuilder getSubscriptionTreePullConsumerBuilder() {
    return new SubscriptionTreePullConsumerBuilder()
        .host(subscription.getHost())
        .port(subscription.getPort())
        .consumerId(subscription.getConsumerId())
        .consumerGroupId(subscription.getGroupId())
        .heartbeatIntervalMs(subscription.getHeartbeatIntervalMs())
        .endpointsSyncIntervalMs(subscription.getEndpointsSyncIntervalMs())
        .thriftMaxFrameSize(subscription.getThriftMaxFrameSize())
        .maxPollParallelism(subscription.getMaxPollParallelism())
        .autoCommit(autoCommit)
        .autoCommitIntervalMs(autoCommitIntervalMs);
  }

  protected SubscriptionTablePullConsumerBuilder getSubscriptionTablePullConsumerBuilder() {
    return new SubscriptionTablePullConsumerBuilder()
        .host(subscription.getHost())
        .port(subscription.getPort())
        .consumerId(subscription.getConsumerId())
        .consumerGroupId(subscription.getGroupId())
        .heartbeatIntervalMs(subscription.getHeartbeatIntervalMs())
        .endpointsSyncIntervalMs(subscription.getEndpointsSyncIntervalMs())
        .thriftMaxFrameSize(subscription.getThriftMaxFrameSize())
        .maxPollParallelism(subscription.getMaxPollParallelism())
        .autoCommit(autoCommit)
        .autoCommitIntervalMs(autoCommitIntervalMs);
  }
}
