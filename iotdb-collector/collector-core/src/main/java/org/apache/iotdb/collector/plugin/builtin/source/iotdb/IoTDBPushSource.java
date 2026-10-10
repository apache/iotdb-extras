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
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.rpc.subscription.config.ConsumerConstant;
import org.apache.iotdb.rpc.subscription.config.TopicConstant;
import org.apache.iotdb.session.subscription.ISubscriptionTreeSession;
import org.apache.iotdb.session.subscription.SubscriptionTreeSessionBuilder;
import org.apache.iotdb.session.subscription.consumer.tree.SubscriptionTreePullConsumer;
import org.apache.iotdb.session.subscription.model.Topic;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessage;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessageType;

import org.apache.tsfile.write.record.Tablet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
  private SubscriptionTreePullConsumer consumer;
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
    if (workerThread != null && workerThread.isAlive()) {
      return;
    }

    // Validate the topic and subscribe on the calling thread so that a missing topic, a
    // tsfile-format topic, an unreachable broker or a server without subscription support fails
    // task creation with the cause, instead of leaving a task that looks alive but never delivers.
    requireRecordFormatTopic();

    final Properties pullProperties = new Properties();
    pullProperties.put(IoTDBPushSourceConstant.HOST_KEY, host);
    pullProperties.put(IoTDBPushSourceConstant.PORT_KEY, port);
    pullProperties.put(ConsumerConstant.CONSUMER_ID_KEY, "r1");
    pullProperties.put(ConsumerConstant.CONSUMER_GROUP_ID_KEY, "rg1");

    final SubscriptionTreePullConsumer pullConsumer =
        new SubscriptionTreePullConsumer(pullProperties);
    try {
      pullConsumer.open();
      pullConsumer.subscribe(topic);
    } catch (final Exception e) {
      try {
        pullConsumer.close();
      } catch (final Exception closeException) {
        e.addSuppressed(closeException);
      }
      throw e;
    }

    consumer = pullConsumer;
    isStarted = true;
    workerThread = new Thread(this::doWork, "iotdb-push-source-" + topic);
    workerThread.start();
  }

  private void requireRecordFormatTopic() throws Exception {
    try (final ISubscriptionTreeSession session =
        new SubscriptionTreeSessionBuilder().host(host).port(port).build()) {
      session.open();
      final Optional<Topic> found = session.getTopic(topic);
      if (!found.isPresent()) {
        throw new IllegalArgumentException(
            String.format(
                "Topic %s does not exist on %s:%d; create it with format=%s before starting the"
                    + " collector IoTDB source",
                topic, host, port, TopicConstant.FORMAT_RECORD_HANDLER_VALUE));
      }
      final String attributes = String.valueOf(found.get().getTopicAttributes());
      if (attributes.toLowerCase(Locale.ROOT).contains("tsfilehandler")) {
        throw new IllegalArgumentException(
            String.format(
                "Topic %s delivers tsfile messages (%s); the collector IoTDB source only consumes"
                    + " record-format messages, create the topic with format=%s",
                topic, attributes, TopicConstant.FORMAT_RECORD_HANDLER_VALUE));
      }
    }
  }

  private void doWork() {
    try (final SubscriptionTreePullConsumer pullConsumer = consumer) {
      while (isStarted && !Thread.currentThread().isInterrupted()) {
        markPausePosition();

        final List<SubscriptionMessage> messages = pullConsumer.poll(timeout);
        for (final SubscriptionMessage message : messages) {
          final short messageType = message.getMessageType();
          if (messageType == SubscriptionMessageType.RECORD_HANDLER.getType()) {
            final Iterator<Tablet> tablets = message.getRecordTabletIterator();
            while (tablets.hasNext()) {
              supply(new SubDemoEvent(tablets.next(), deviceId));
            }
          } else if (messageType != SubscriptionMessageType.WATERMARK.getType()) {
            throw new UnsupportedOperationException(
                String.format(
                    "Topic %s delivered a message of type %d; the collector IoTDB source only"
                        + " consumes record-format messages (format=%s)",
                    topic, messageType, TopicConstant.FORMAT_RECORD_HANDLER_VALUE));
          }
        }
      }
    } catch (final Exception e) {
      Thread.currentThread().interrupt();
      if (isStarted) {
        LOGGER.error(
            "The collector IoTDB source for topic {} stopped consuming; drop and recreate the task"
                + " after fixing the cause",
            topic,
            e);
      } else {
        LOGGER.info("The collector IoTDB source for topic {} stopped", topic);
      }
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
