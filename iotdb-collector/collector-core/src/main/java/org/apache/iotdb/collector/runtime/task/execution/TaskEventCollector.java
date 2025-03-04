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

package org.apache.iotdb.collector.runtime.task.execution;

import org.apache.iotdb.pipe.api.collector.EventCollector;
import org.apache.iotdb.pipe.api.event.Event;

import com.lmax.disruptor.RingBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskEventCollector implements EventCollector {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskEventCollector.class);
  private final RingBuffer<TaskEventContainer> ringBuffer;

  public TaskEventCollector(final RingBuffer<TaskEventContainer> ringBuffer) {
    this.ringBuffer = ringBuffer;
  }

  @Override
  public void collect(final Event event) {
    ringBuffer.publishEvent((container, sequence, o) -> container.setEvent(event), event);
    LOGGER.info("successfully publish event {}", event);
  }
}
