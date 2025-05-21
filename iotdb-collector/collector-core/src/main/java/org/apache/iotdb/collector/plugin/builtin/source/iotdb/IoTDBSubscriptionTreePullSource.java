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

import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.session.subscription.consumer.ISubscriptionTreePullConsumer;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessage;
import org.apache.iotdb.session.subscription.payload.SubscriptionSessionDataSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class IoTDBSubscriptionTreePullSource extends IoTDBSubscriptionPullSource {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(IoTDBSubscriptionTreePullSource.class);

  private ISubscriptionTreePullConsumer consumer;
  private static final Long POLL_TIMEOUT_MS = 10_000L;

  private volatile boolean isStarted;
  private Thread workerThread;

  @Override
  public void start() throws Exception {
    consumer = getSubscriptionPullConsumerBuilder().buildPullConsumer();
    consumer.open();
    consumer.subscribe(subscription.getTopic());

    if (workerThread == null) {
      isStarted = true;

      workerThread = new Thread(this::doWork);
      workerThread.setName("iotdb-subscription-tree-pull-source");
      workerThread.start();
    }
  }

  private void doWork() {
    while (isStarted && !Thread.currentThread().isInterrupted()) {
      final List<SubscriptionMessage> messages = consumer.poll(POLL_TIMEOUT_MS);

      for (final SubscriptionMessage message : messages) {
        for (final SubscriptionSessionDataSet dataSet : message.getSessionDataSetsHandler()) {
          subscription.checkIfNeedPause();

          try {
            subscription.put(new PipeRawTabletInsertionEvent(dataSet.getTablet(), isAligned));
          } catch (final InterruptedException e) {
            LOGGER.warn("iotdb subscription tree model pull consumer thread interrupted", e);
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

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

  @Override
  public Optional<ProgressIndex> report() {
    return Optional.empty();
  }
}
