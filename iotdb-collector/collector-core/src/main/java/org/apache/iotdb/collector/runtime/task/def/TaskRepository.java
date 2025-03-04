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

public class TaskRepository {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskRepository.class);

  private final TaskComponent sourceComponent;
  private final TaskComponent processorComponent;
  private final TaskComponent sinkComponent;

  public TaskRepository(
      final TaskComponent sourceComponent,
      final TaskComponent processorComponent,
      final TaskComponent sinkComponent) {
    this.sourceComponent = sourceComponent;
    this.processorComponent = processorComponent;
    this.sinkComponent = sinkComponent;
  }

  // Disruptor consumers must be started before producers
  public void create() {
    try {
      sinkComponent.create();
      processorComponent.create();
      sourceComponent.create();
    } catch (final Exception e) {
      LOGGER.warn("Failed to create task", e);
    }
  }

  // Disruptor consumers must be started before producers
  public void start() {
    try {
      sinkComponent.start();
      processorComponent.start();
      sourceComponent.start();
    } catch (final Exception e) {
      LOGGER.warn("Failed to start task", e);
    }
  }

  public void stop() {
    try {
      sourceComponent.stop();
      processorComponent.stop();
      sinkComponent.stop();
    } catch (final Exception e) {
      LOGGER.warn("Failed to stop task", e);
    }
  }

  public void drop() {
    try {
      sourceComponent.drop();
      processorComponent.drop();
      sinkComponent.drop();
    } catch (final Exception e) {
      LOGGER.warn("Failed to drop task", e);
    }
  }
}
