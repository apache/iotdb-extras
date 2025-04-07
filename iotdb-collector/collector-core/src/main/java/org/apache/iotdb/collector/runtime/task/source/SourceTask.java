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

package org.apache.iotdb.collector.runtime.task.source;

import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.task.Task;
import org.apache.iotdb.collector.runtime.task.TaskStateEnum;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.source.pull.PullSourceTask;
import org.apache.iotdb.collector.runtime.task.source.push.PushSourceTask;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import java.util.Map;

import static org.apache.iotdb.collector.config.TaskRuntimeOptions.TASK_SOURCE_PARALLELISM_NUM;

public abstract class SourceTask extends Task {

  protected final EventCollector processorProducer;
  protected TaskStateEnum taskState;

  protected SourceTask(
      final String taskId,
      final Map<String, String> attributes,
      final EventCollector processorProducer,
      final TaskStateEnum taskState) {
    super(
        taskId, attributes, TASK_SOURCE_PARALLELISM_NUM.key(), TASK_SOURCE_PARALLELISM_NUM.value());
    this.processorProducer = processorProducer;
    this.taskState = taskState;
  }

  public static SourceTask construct(
      final String taskId,
      final Map<String, String> attributes,
      final EventCollector processorProducer,
      final TaskStateEnum taskState)
      throws Exception {
    final PluginRuntime pluginRuntime =
        RuntimeService.plugin().isPresent() ? RuntimeService.plugin().get() : null;
    if (pluginRuntime == null) {
      throw new IllegalStateException("Plugin runtime is down");
    }

    final PipeParameters parameters = new PipeParameters(attributes);
    if (pluginRuntime.isPullSource(parameters)) {
      return new PullSourceTask(taskId, attributes, processorProducer, taskState);
    }
    if (pluginRuntime.isPushSource(parameters)) {
      return new PushSourceTask(taskId, attributes, processorProducer, taskState);
    }
    throw new IllegalArgumentException("Unsupported source type");
  }
}
