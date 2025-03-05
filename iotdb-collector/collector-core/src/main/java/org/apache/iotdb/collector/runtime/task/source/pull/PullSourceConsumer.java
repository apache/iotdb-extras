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

package org.apache.iotdb.collector.runtime.task.source.pull;

import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.pipe.api.collector.EventCollector;

class PullSourceConsumer {

  private final PullSource pullSource;
  private final EventCollector eventCollector;

  PullSourceConsumer(final PullSource pullSource, final EventCollector eventCollector) {
    this.pullSource = pullSource;
    this.eventCollector = eventCollector;
  }

  PullSource consumer() {
    return pullSource;
  }

  public void onScheduler() throws Exception {
    // TODO: scheduler strategy
    eventCollector.collect(pullSource.supply());
  }
}
