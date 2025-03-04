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

package org.apache.iotdb.collector.runtime.task.def;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskCombiner {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskCombiner.class);

  private final Task source;
  private final Task processor;
  private final Task sink;

  public TaskCombiner(final Task source, final Task processor, final Task sink) {
    this.source = source;
    this.processor = processor;
    this.sink = sink;
  }

  // Disruptor consumers must be started before producers
  public void create() {
    try {
      sink.create();
      processor.create();
      source.create();
    } catch (final Exception e) {
      LOGGER.warn("Failed to create task", e);
    }
  }

  // Disruptor consumers must be started before producers
  public void start() {
    try {
      sink.start();
      processor.start();
      source.start();
    } catch (final Exception e) {
      LOGGER.warn("Failed to start task", e);
    }
  }

  public void stop() {
    try {
      source.stop();
      processor.stop();
      sink.stop();
    } catch (final Exception e) {
      LOGGER.warn("Failed to stop task", e);
    }
  }

  public void drop() {
    try {
      source.drop();
      processor.drop();
      sink.drop();
    } catch (final Exception e) {
      LOGGER.warn("Failed to drop task", e);
    }
  }
}
