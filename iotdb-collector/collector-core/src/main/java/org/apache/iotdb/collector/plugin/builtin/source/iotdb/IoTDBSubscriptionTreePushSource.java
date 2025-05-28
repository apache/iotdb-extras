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

import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.session.subscription.consumer.ISubscriptionTreePushConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class IoTDBSubscriptionTreePushSource extends IoTDBSubscriptionPushSource {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(IoTDBSubscriptionTreePushSource.class);

  protected void doWork() {
    try (final ISubscriptionTreePushConsumer consumer =
        getSubscriptionTreePushConsumerBuilder().buildPushConsumer()) {
      consumer.open();
      consumer.subscribe(subscription.getTopic());
    } catch (final Exception e) {
      LOGGER.warn("Error occurred while {} thread", getPushConsumerThreadName(), e);
    }
  }

  @Override
  protected String getPushConsumerThreadName() {
    return "iotdb-subscription-tree-push-source";
  }

  @Override
  public Optional<ProgressIndex> report() {
    return Optional.empty();
  }
}
