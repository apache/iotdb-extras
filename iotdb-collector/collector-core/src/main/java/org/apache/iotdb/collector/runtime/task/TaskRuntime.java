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

package org.apache.iotdb.collector.runtime.task;

import org.apache.iotdb.collector.runtime.task.def.TaskCombiner;
import org.apache.iotdb.collector.runtime.task.def.processor.ProcessorTask;
import org.apache.iotdb.collector.runtime.task.def.sink.SinkTask;
import org.apache.iotdb.collector.runtime.task.def.source.PushSourceTask;
import org.apache.iotdb.collector.runtime.task.def.source.SourceTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskRuntime implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskRuntime.class);

  private final Map<String, TaskCombiner> tasks = new ConcurrentHashMap<>();

  public Response createTask(
      final String taskId,
      final Map<String, String> sourceAttribute,
      final Map<String, String> processorAttribute,
      final Map<String, String> sinkAttribute) {
    try {
      if (validateTaskIsExist(taskId)) {
        return Response.status(Response.Status.CONFLICT)
            .entity(String.format("task %s has existed", taskId))
            .build();
      }

      final SinkTask sinkTask = new SinkTask(sinkAttribute);
      final ProcessorTask processorTask = new ProcessorTask(processorAttribute, sinkTask);
      final SourceTask sourceTask = new PushSourceTask(taskId, sourceAttribute, processorTask);
      final TaskCombiner taskRepository = new TaskCombiner(sourceTask, processorTask, sinkTask);

      tasks.put(taskId, taskRepository);
      taskRepository.create();

      LOGGER.info("Successfully created task {}", taskId);
      return Response.status(Response.Status.CREATED)
          .entity(String.format("Successfully created task %s", taskId))
          .build();
    } catch (final Exception e) {
      LOGGER.warn("Failed to create task", e);
      return Response.serverError()
          .entity(String.format("Failed to create task %s, because %s", taskId, e.getMessage()))
          .build();
    }
  }

  public boolean alterTask() {
    return true;
  }

  public Response startTask(final String taskId) {
    if (!validateTaskIsExist(taskId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(String.format("task %s not found", taskId))
          .build();
    }

    tasks.get(taskId).start();
    LOGGER.info("Task {} started successfully", taskId);
    return Response.status(Response.Status.OK)
        .entity(String.format("task %s start successfully", taskId))
        .build();
  }

  public Response stopTask(final String taskId) {
    if (!validateTaskIsExist(taskId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(String.format("task %s not found", taskId))
          .build();
    }

    try {
      final TaskCombiner taskRepository = tasks.get(taskId);
      if (taskRepository != null) {
        taskRepository.stop();
      }
    } catch (final Exception e) {
      LOGGER.warn("Failed to stop task", e);
      return Response.serverError()
          .entity(String.format("Failed to stop task %s, because %s", taskId, e.getMessage()))
          .build();
    }

    LOGGER.info("Task {} stopped successfully", taskId);
    return Response.status(Response.Status.OK)
        .entity(String.format("task %s stop successfully", taskId))
        .build();
  }

  public Response dropTask(final String taskId) {
    if (!validateTaskIsExist(taskId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(String.format("task %s not found", taskId))
          .build();
    }

    tasks.remove(taskId).drop();
    LOGGER.info("Task {} dropped successfully", taskId);
    return Response.status(Response.Status.OK)
        .entity(String.format("task %s drop successfully", taskId))
        .build();
  }

  private boolean validateTaskIsExist(final String taskId) {
    if (tasks.containsKey(taskId)) {
      return true;
    }
    LOGGER.warn("Task {} not found", taskId);
    return false;
  }

  @Override
  public void close() throws Exception {}
}
