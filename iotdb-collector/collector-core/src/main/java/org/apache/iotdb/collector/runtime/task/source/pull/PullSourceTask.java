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

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.api.PullSource;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_REPORT_TIME_INTERVAL_KEY;

public class PullSourceTask extends SourceTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(PullSourceTask.class);

  private static final Map<String, ExecutorService> REGISTERED_EXECUTOR_SERVICES =
      new ConcurrentHashMap<>();

  private PullSourceConsumer[] consumers;

  public PullSourceTask(
      final String taskId,
      final Map<String, String> attributes,
      final EventCollector processorProducer,
      final TaskStateEnum taskState) {
    super(taskId, attributes, processorProducer, taskState);
  }

  @Override
  public void createInternal() throws Exception {
    final PluginRuntime pluginRuntime =
        RuntimeService.plugin().isPresent() ? RuntimeService.plugin().get() : null;
    if (pluginRuntime == null) {
      throw new IllegalStateException("Plugin runtime is down");
    }

    String pullSourceCreateErrorMsg = "";

    REGISTERED_EXECUTOR_SERVICES.putIfAbsent(
        taskId,
        new ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(parallelism))); // TODO: thread name

    final long creationTime = System.currentTimeMillis();
    consumers = new PullSourceConsumer[parallelism];
    for (int i = 0; i < parallelism; i++) {
      consumers[i] =
          new PullSourceConsumer(
              (PullSource) pluginRuntime.constructSource(parameters), processorProducer);
      try {
        consumers[i].consumer().validate(new PipeParameterValidator(parameters));
        consumers[i]
            .consumer()
            .customize(
                parameters,
                new CollectorSourceRuntimeConfiguration(taskId, creationTime, parallelism, i));
        consumers[i].consumer().start();
      } catch (final Exception e) {
        try {
          pullSourceCreateErrorMsg =
              String.format(
                  "Error occurred when creating pull-source-task-%s, instance index %s, because %s tying to close it.",
                  taskId, i, e);
          LOGGER.warn(pullSourceCreateErrorMsg);

          consumers[i].consumer().close();
        } catch (final Exception ex) {
          final String pullSourceCloseErrorMsg =
              String.format(
                  "Error occurred when closing pull-source-task-%s, instance index %s, because %s",
                  taskId, i, ex);
          LOGGER.warn(pullSourceCloseErrorMsg);

          throw new RuntimeException(pullSourceCreateErrorMsg + "\n" + pullSourceCloseErrorMsg);
        }

        throw new RuntimeException(pullSourceCreateErrorMsg);
      }

      int finalI = i;
      REGISTERED_EXECUTOR_SERVICES
          .get(taskId)
          .submit(
              () -> {
                while (dispatch.isRunning() && TaskStateEnum.RUNNING.equals(taskState)) {
                  try {
                    consumers[finalI].onScheduler();
                  } catch (final Exception e) {
                    LOGGER.warn("Failed to pull source", e);
                  }

                  dispatch.waitUntilRunningOrDropped();
                }
              });
    }

    // register storage progress schedule job
    ScheduleService.reportProgress()
        .ifPresent(
            reportEvent -> {
              reportEvent.register(
                  taskId,
                  () -> {
                    if (consumers != null && consumers.length > 0) {
                      Map<Integer, ProgressIndex> progresses = new HashMap<>();
                      for (int i = 0; i < consumers.length; i++) {
                        final int finalI = i;
                        consumers[i]
                            .consumer()
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
                  consumers[0].consumer().report().isPresent()
                      ? Integer.parseInt(
                          consumers[0]
                              .consumer()
                              .report()
                              .get()
                              .getProgressInfo()
                              .getOrDefault(
                                  SOURCE_REPORT_TIME_INTERVAL_KEY,
                                  String.valueOf(
                                      TaskRuntimeOptions.TASK_PROGRESS_REPORT_INTERVAL.value())))
                      : TaskRuntimeOptions.TASK_PROGRESS_REPORT_INTERVAL.value());
            });
  }

  @Override
  public void startInternal() {
    this.taskState = TaskStateEnum.RUNNING;
  }

  @Override
  public void stopInternal() {
    this.taskState = TaskStateEnum.STOPPED;
  }

  @Override
  public void dropInternal() {
    if (consumers != null) {
      for (int i = 0; i < parallelism; i++) {
        try {
          consumers[i].consumer().close();
        } catch (final Exception e) {
          LOGGER.warn("Failed to close source", e);
        }
      }
    }

    final ExecutorService executorService = REGISTERED_EXECUTOR_SERVICES.remove(taskId);
    if (executorService != null) {
      executorService.shutdown();
    }
  }
}
