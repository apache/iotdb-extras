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

import org.apache.iotdb.collector.runtime.task.execution.TaskEventCollector;
import org.apache.iotdb.collector.runtime.task.execution.TaskEventConsumer;
import org.apache.iotdb.collector.runtime.task.execution.TaskEventConsumerController;
import org.apache.iotdb.pipe.api.PipePlugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.stream.Stream;

public abstract class Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);

  public abstract void create() throws Exception;

  public abstract void start() throws Exception;

  public abstract void stop() throws Exception;

  public abstract void drop() throws Exception;

  protected TaskEventConsumer[] getConsumer(
      final PipePlugin plugin, final int consumerNum, final TaskEventCollector collector) {
    return Stream.generate(() -> createConsumer(plugin, collector))
        .limit(consumerNum)
        .toArray(TaskEventConsumer[]::new);
  }

  private TaskEventConsumer createConsumer(
      final PipePlugin plugin, final TaskEventCollector collector) {
    return Objects.nonNull(collector)
        ? new TaskEventConsumer(plugin, collector, new TaskEventConsumerController())
        : new TaskEventConsumer(plugin, new TaskEventConsumerController());
  }
}
