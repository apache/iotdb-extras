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

package org.apache.iotdb.collector.plugin.builtin.source;

import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.builtin.source.constant.IoTDBPushSourceConstant;
import org.apache.iotdb.collector.plugin.builtin.source.event.SubDemoEvent;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.rpc.subscription.config.ConsumerConstant;
import org.apache.iotdb.session.subscription.consumer.tree.SubscriptionTreePullConsumer;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessage;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessageType;
import org.apache.iotdb.session.subscription.payload.SubscriptionSessionDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class IoTDBPushSource extends PushSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBPushSource.class);

  private String host;
  private Integer port;
  private String topic;
  private Long timeout;
  private String deviceId;

  private volatile boolean isStarted = true;
  private Thread workerThread;

  @Override
  public void validate(PipeParameterValidator validator) throws Exception {}

  @Override
  public void customize(
      PipeParameters pipeParameters, PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    host =
        pipeParameters.getStringOrDefault(
            IoTDBPushSourceConstant.HOST_KEY, IoTDBPushSourceConstant.HOST_VALUE);
    port =
        pipeParameters.getIntOrDefault(
            IoTDBPushSourceConstant.PORT_KEY, IoTDBPushSourceConstant.PORT_VALUE);
    topic =
        pipeParameters.getStringOrDefault(
            IoTDBPushSourceConstant.TOPIC_KEY, IoTDBPushSourceConstant.TOPIC_VALUE);
    timeout =
        pipeParameters.getLongOrDefault(
            IoTDBPushSourceConstant.TIMEOUT_KEY, IoTDBPushSourceConstant.TIMEOUT_VALUE);
    deviceId =
        pipeParameters.getStringOrDefault(
            IoTDBPushSourceConstant.DEVICE_ID_KEY, IoTDBPushSourceConstant.DEVICE_ID_VALUE);
  }

  @Override
  public void start() throws Exception {
    if (workerThread == null || !workerThread.isAlive()) {
      isStarted = true;
      workerThread = new Thread(this::doWork);
      workerThread.start();
    }
  }

  private void doWork() {
    final Properties pullProperties = new Properties();
    pullProperties.put(IoTDBPushSourceConstant.HOST_KEY, host);
    pullProperties.put(IoTDBPushSourceConstant.PORT_KEY, port);
    pullProperties.put(ConsumerConstant.CONSUMER_ID_KEY, "r1");
    pullProperties.put(ConsumerConstant.CONSUMER_GROUP_ID_KEY, "rg1");

    try (final SubscriptionTreePullConsumer consumer =
        new SubscriptionTreePullConsumer(pullProperties)) {
      consumer.open();
      consumer.subscribe(topic);

      while (isStarted && !Thread.currentThread().isInterrupted()) {
        markPausePosition();

        final List<SubscriptionMessage> messages = consumer.poll(timeout);
        for (final SubscriptionMessage message : messages) {
          final short messageType = message.getMessageType();
          if (SubscriptionMessageType.isValidatedMessageType(messageType)) {
            for (final SubscriptionSessionDataSet dataSet : message.getSessionDataSetsHandler()) {
              final SubDemoEvent event = new SubDemoEvent(dataSet.getTablet(), deviceId);
              supply(event);
            }
          }
        }
      }
    } catch (final Exception e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Error in push source", e);
    }
  }

  @Override
  public void close() throws Exception {
    isStarted = false;
    if (workerThread != null) {
      workerThread.interrupt();
      try {
        workerThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      workerThread = null;
    }
  }

  @Override
  public Optional<ProgressIndex> report() {
    return Optional.empty();
  }
}
