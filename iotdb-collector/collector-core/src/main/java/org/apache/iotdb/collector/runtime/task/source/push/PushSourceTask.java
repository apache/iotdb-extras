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

package org.apache.iotdb.collector.runtime.task.source.push;

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorSourceRuntimeConfiguration;
import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.collector.runtime.task.TaskStateEnum;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.event.ProgressReportEvent;
import org.apache.iotdb.collector.runtime.task.source.SourceTask;
import org.apache.iotdb.collector.service.PersistenceService;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.collector.service.ScheduleService;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class PushSourceTask extends SourceTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(PushSourceTask.class);

  private PushSource[] pushSources;

  public PushSourceTask(
      final String taskId,
      final Map<String, String> sourceParams,
      final EventCollector processorProducer,
      final TaskStateEnum taskState) {
    super(taskId, sourceParams, processorProducer, taskState);
  }

  @Override
  public void createInternal() throws Exception {
    final PluginRuntime pluginRuntime =
        RuntimeService.plugin().isPresent() ? RuntimeService.plugin().get() : null;
    if (pluginRuntime == null) {
      throw new IllegalStateException("Plugin runtime is down");
    }

    final long creationTime = System.currentTimeMillis();
    pushSources = new PushSource[parallelism];
    for (int i = 0; i < parallelism; i++) {
      pushSources[i] = (PushSource) pluginRuntime.constructSource(parameters);
      pushSources[i].setCollector(processorProducer);
      pushSources[i].setDispatch(dispatch);
      try {
        pushSources[i].validate(new PipeParameterValidator(parameters));
        pushSources[i].customize(
            parameters,
            new CollectorSourceRuntimeConfiguration(taskId, creationTime, parallelism, i));
        if (TaskStateEnum.STOPPED.equals(taskState)) {
          pushSources[i].pause();
        }
        pushSources[i].start();
      } catch (final Exception e) {
        try {
          pushSources[i].close();
        } catch (final Exception ex) {
          LOGGER.warn("Failed to close source on creation failure", ex);
          throw e;
        }
      }
    }

    // register storage progress schedule job
    ScheduleService.reportProgress()
        .ifPresent(
            reportEvent ->
                reportEvent.register(
                    taskId,
                    () -> {
                      if (pushSources != null && pushSources.length > 0) {
                        Map<Integer, ProgressIndex> progresses = new HashMap<>();
                        for (int i = 0; i < pushSources.length; i++) {
                          final int finalI = i;
                          pushSources[i]
                              .report()
                              .ifPresent(progressIndex -> progresses.put(finalI, progressIndex));
                        }

                        PersistenceService.task()
                            .ifPresent(
                                task ->
                                    task.tryReportTaskProgress(
                                        new ProgressReportEvent(taskId, progresses)));
                      }
                    },
                    TaskRuntimeOptions.TASK_PROGRESS_REPORT_INTERVAL.value()));
  }

  @Override
  public void startInternal() {
    if (this.taskState.equals(TaskStateEnum.RUNNING)) {
      return;
    }

    if (pushSources != null) {
      for (int i = 0; i < parallelism; i++) {
        try {
          pushSources[i].resume();
        } catch (final Exception e) {
          LOGGER.warn("Failed to restart push source", e);
          return;
        }
      }
    }

    this.taskState = TaskStateEnum.RUNNING;
  }

  @Override
  public void stopInternal() {
    if (this.taskState.equals(TaskStateEnum.STOPPED)) {
      return;
    }

    if (pushSources != null) {
      for (int i = 0; i < parallelism; i++) {
        try {
          pushSources[i].pause();
        } catch (final Exception e) {
          LOGGER.warn("Failed to stop source", e);
          return;
        }
      }

      this.taskState = TaskStateEnum.STOPPED;
    }
  }

  @Override
  public void dropInternal() {
    if (pushSources != null) {
      for (int i = 0; i < parallelism; i++) {
        try {
          pushSources[i].close();
        } catch (final Exception e) {
          LOGGER.warn("Failed to close source", e);
        }
      }
    }
  }
}
