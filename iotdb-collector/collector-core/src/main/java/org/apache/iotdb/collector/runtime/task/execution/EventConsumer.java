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

import org.apache.iotdb.pipe.api.PipePlugin;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.PipeSink;

import com.lmax.disruptor.WorkHandler;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class EventConsumer implements WorkHandler<EventContainer> {

  private final PipePlugin plugin;
  private final EventCollector collector;

  private final AtomicBoolean running = new AtomicBoolean(true);

  private static final long PARK_NANOS = 100_000_000L;

  public void pause() {
    running.set(false);
  }

  public void resume() {
    running.set(true);
  }

  public boolean shouldRun() {
    while (!running.get()) {
      LockSupport.parkNanos(PARK_NANOS);
    }
    return running.get();
  }

  public EventConsumer(
      final PipePlugin plugin,
      final EventCollector collector) {
    this.plugin = plugin;
    this.collector = collector;
  }

  public EventConsumer(
      final PipePlugin plugin) {
    this(plugin, null);
  }

  @Override
  public void onEvent(final EventContainer eventContainer) throws Exception {
    if (!shouldRun()) {
      return;
    }
    if (plugin instanceof PipeProcessor) {
      ((PipeProcessor) plugin).process(eventContainer.getEvent(), this.collector);
    } else if (plugin instanceof PipeSink) {
      ((PipeSink) plugin).transfer(eventContainer.getEvent());
    }
  }
}
