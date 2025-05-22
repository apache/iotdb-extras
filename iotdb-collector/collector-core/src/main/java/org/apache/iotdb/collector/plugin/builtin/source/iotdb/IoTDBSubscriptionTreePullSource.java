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
import org.apache.iotdb.session.subscription.consumer.ISubscriptionTreePullConsumer;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessage;

import java.util.List;
import java.util.Optional;

public class IoTDBSubscriptionTreePullSource extends IoTDBSubscriptionPullSource {

  private ISubscriptionTreePullConsumer consumer;

  @Override
  protected void initPullConsumer() {
    consumer = getSubscriptionPullConsumerBuilder().buildPullConsumer();
    consumer.open();
    consumer.subscribe(subscription.getTopic());
  }

  @Override
  protected String getPullConsumerThreadName() {
    return "iotdb-subscription-tree-pull-source";
  }

  @Override
  protected List<SubscriptionMessage> poll() {
    return consumer.poll(POLL_TIMEOUT_MS);
  }

  @Override
  public Optional<ProgressIndex> report() {
    return Optional.empty();
  }
}
