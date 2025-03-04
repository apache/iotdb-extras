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

package org.apache.iotdb.collector.runtime.task.def.source;

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.runtime.task.def.Task;
import org.apache.iotdb.collector.runtime.task.def.processor.ProcessorTask;
import org.apache.iotdb.commons.concurrent.IoTThreadFactory;
import org.apache.iotdb.commons.concurrent.threadpool.WrappedThreadPoolExecutor;
import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class SourceTask extends Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(SourceTask.class);

  // Source needs the processor's RingBuffer to publish event.
  protected final ProcessorTask processorTask;
  protected final int sourceParallelismNum;
  protected final String taskId;
  // Store the status of the tasks, Running or Stopped
  protected static final Map<String, TaskState> SOURCE_TASK_STATUS = new ConcurrentHashMap<>();
  protected static final Map<String, ExecutorService> SOURCE_EXECUTOR_SERVICE =
      new ConcurrentHashMap<>();
  // Source tasks list
  protected static final Map<String, List<PipeSource>> SOURCE_TASK = new ConcurrentHashMap<>();

  public SourceTask(
      final String taskId,
      final Map<String, String> sourceParams,
      final ProcessorTask processorTask) {
    this.taskId = taskId;
    final PipeParameters params = new PipeParameters(sourceParams);
    this.sourceParallelismNum =
        params.getIntOrDefault(
            TaskRuntimeOptions.TASK_SOURCE_PARALLELISM_NUM.key(),
            TaskRuntimeOptions.TASK_SOURCE_PARALLELISM_NUM.value());
    this.processorTask = processorTask;
  }

  @Override
  public void startInternal() throws Exception {
    SOURCE_TASK_STATUS.computeIfPresent(taskId, (taskId, status) -> TaskState.Running);
    SOURCE_TASK
        .get(taskId)
        .forEach(
            source -> {
              try {
                source.start();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
  }

  @Override
  public synchronized void stopInternal() {
    SOURCE_TASK_STATUS.computeIfPresent(taskId, (taskId, status) -> TaskState.Stopped);
    SOURCE_TASK
        .get(taskId)
        .forEach(
            source -> {
              try {
                source.close();
              } catch (Exception e) {
                LOGGER.warn("Failed to close source", e);
              }
            });
  }

  @Override
  public void dropInternal() {
    stopInternal();
    SOURCE_TASK.remove(taskId);
    SOURCE_TASK_STATUS.remove(taskId);
    SOURCE_EXECUTOR_SERVICE.remove(taskId).shutdown();
  }

  protected void addSourceTask(final PipeSource source) {
    if (!SOURCE_TASK.containsKey(taskId)) {
      SOURCE_TASK.put(taskId, new CopyOnWriteArrayList<>());
    }
    SOURCE_TASK.get(taskId).add(source);
  }

  protected void createSourceTask() {
    SOURCE_EXECUTOR_SERVICE.putIfAbsent(
        taskId,
        new WrappedThreadPoolExecutor(
            sourceParallelismNum,
            sourceParallelismNum,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new IoTThreadFactory("source-executor"),
            "source-executor"));
    SOURCE_TASK_STATUS.putIfAbsent(taskId, TaskState.Running);
  }

  protected enum TaskState {
    Running,
    Stopped
  }
}
