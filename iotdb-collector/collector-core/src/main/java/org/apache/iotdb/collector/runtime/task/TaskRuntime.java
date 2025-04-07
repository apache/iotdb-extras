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

import org.apache.iotdb.collector.runtime.task.processor.ProcessorTask;
import org.apache.iotdb.collector.runtime.task.sink.SinkTask;
import org.apache.iotdb.collector.runtime.task.source.SourceTask;
import org.apache.iotdb.collector.service.PersistenceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class TaskRuntime implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskRuntime.class);

  private final Map<String, TaskCombiner> tasks = new ConcurrentHashMap<>();

  public synchronized Response createTask(
      final String taskId,
      final TaskStateEnum taskState,
      final Map<String, String> sourceAttribute,
      final Map<String, String> processorAttribute,
      final Map<String, String> sinkAttribute,
      final boolean isRestRequest) {
    try {
      if (tasks.containsKey(taskId)) {
        return Response.status(Response.Status.CONFLICT)
            .entity(String.format("task %s has existed", taskId))
            .build();
      }

      final SinkTask sinkTask = new SinkTask(taskId, sinkAttribute);
      final ProcessorTask processorTask =
          new ProcessorTask(taskId, processorAttribute, sinkTask.makeProducer());
      final SourceTask sourceTask =
          SourceTask.construct(taskId, sourceAttribute, processorTask.makeProducer(), taskState);

      final TaskCombiner taskCombiner = new TaskCombiner(sourceTask, processorTask, sinkTask);
      taskCombiner.create();

      tasks.put(taskId, taskCombiner);

      // storage task info to sqlite
      if (isRestRequest) {
        PersistenceService.task()
            .ifPresent(
                taskPersistence ->
                    taskPersistence.tryPersistenceTask(
                        taskId,
                        TaskStateEnum.RUNNING,
                        sourceAttribute,
                        processorAttribute,
                        sinkAttribute));
      }

      LOGGER.info("Successfully created task {}", taskId);
      return Response.status(Response.Status.OK)
          .entity(String.format("Successfully created task %s", taskId))
          .build();
    } catch (final Exception e) {
      LOGGER.warn("Failed to create task {} because {}", taskId, e.getMessage(), e);
      return Response.serverError()
          .entity(String.format("Failed to create task %s, because %s", taskId, e.getMessage()))
          .build();
    }
  }

  public synchronized Response startTask(final String taskId) {
    if (!tasks.containsKey(taskId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(String.format("task %s not found", taskId))
          .build();
    }

    try {
      tasks.get(taskId).start();

      PersistenceService.task()
          .ifPresent(
              taskPersistence -> taskPersistence.tryAlterTaskState(taskId, TaskStateEnum.RUNNING));

      LOGGER.info("Task {} start successfully", taskId);
      return Response.status(Response.Status.OK)
          .entity(String.format("task %s start successfully", taskId))
          .build();
    } catch (Exception e) {
      LOGGER.warn("Failed to start task {} because {}", taskId, e.getMessage(), e);
      return Response.serverError()
          .entity(String.format("Failed to start task %s, because %s", taskId, e.getMessage()))
          .build();
    }
  }

  public synchronized Response stopTask(final String taskId) {
    if (!tasks.containsKey(taskId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(String.format("task %s not found", taskId))
          .build();
    }

    try {
      tasks.get(taskId).stop();

      PersistenceService.task()
          .ifPresent(
              taskPersistence -> taskPersistence.tryAlterTaskState(taskId, TaskStateEnum.STOPPED));

      LOGGER.info("Task {} stop successfully", taskId);
      return Response.status(Response.Status.OK)
          .entity(String.format("task %s stop successfully", taskId))
          .build();
    } catch (final Exception e) {
      LOGGER.warn("Failed to stop task {} because {}", taskId, e.getMessage(), e);
      return Response.serverError()
          .entity(String.format("Failed to stop task %s, because %s", taskId, e.getMessage()))
          .build();
    }
  }

  public synchronized Response dropTask(final String taskId) {
    if (Objects.isNull(tasks.get(taskId)) || !tasks.containsKey(taskId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(String.format("task %s not found", taskId))
          .build();
    }

    try {
      final TaskCombiner task = tasks.get(taskId);
      task.drop();
      tasks.remove(taskId);

      // remove task info from sqlite
      PersistenceService.task().ifPresent(taskPersistence -> taskPersistence.tryDeleteTask(taskId));

      LOGGER.info("Task {} drop successfully", taskId);
      return Response.status(Response.Status.OK)
          .entity(String.format("task %s drop successfully", taskId))
          .build();
    } catch (final Exception e) {
      LOGGER.warn("Failed to drop task {} because {}", taskId, e.getMessage(), e);
      return Response.serverError()
          .entity(String.format("Failed to drop task %s, because %s", taskId, e.getMessage()))
          .build();
    }
  }

  @Override
  public synchronized void close() throws Exception {
    final long currentTime = System.currentTimeMillis();
    for (final TaskCombiner taskCombiner : tasks.values()) {
      taskCombiner.drop();
    }
    tasks.clear();
    LOGGER.info("Task runtime closed in {}ms", System.currentTimeMillis() - currentTime);
  }
}
