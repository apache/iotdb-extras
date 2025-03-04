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

package org.apache.iotdb.collector.runtime.task.datastructure;

import org.apache.iotdb.pipe.api.PipePlugin;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.PipeSink;

import com.lmax.disruptor.WorkHandler;

public class TaskEventConsumer implements WorkHandler<TaskEventContainer> {

  private final PipePlugin plugin;
  private final TaskEventCollector collector;
  private final TaskEventConsumerController consumerController;

  public TaskEventConsumer(
      final PipePlugin plugin,
      final TaskEventCollector collector,
      final TaskEventConsumerController consumerController) {
    this.plugin = plugin;
    this.collector = collector;
    this.consumerController = consumerController;
  }

  public TaskEventConsumer(
      final PipePlugin plugin, final TaskEventConsumerController consumerController) {
    this(plugin, null, consumerController);
  }

  @Override
  public void onEvent(final TaskEventContainer taskEventContainer) throws Exception {
    if (!consumerController.shouldRun()) {
      return;
    }
    if (plugin instanceof PipeProcessor) {
      ((PipeProcessor) plugin).process(taskEventContainer.getEvent(), this.collector);
    } else if (plugin instanceof PipeSink) {
      ((PipeSink) plugin).transfer(taskEventContainer.getEvent());
    }
  }

  public TaskEventConsumerController getConsumerController() {
    return consumerController;
  }
}
