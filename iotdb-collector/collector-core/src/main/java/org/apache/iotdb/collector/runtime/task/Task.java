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

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public abstract class Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);

  protected final String taskId;
  protected final PipeParameters parameters;

  protected final int parallelism;

  private static final long CHECK_RUNNING_INTERVAL_NANOS = 100_000_000L;
  protected final AtomicBoolean isRunning = new AtomicBoolean(false);
  protected final AtomicBoolean isDropped = new AtomicBoolean(false);

  protected Task(final String taskId, final Map<String, String> attributes) {
    this.taskId = taskId;
    this.parameters = new PipeParameters(attributes);

    this.parallelism =
        parameters.getIntOrDefault(
            TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.key(),
            TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.value());
  }

  public void resume() {
    isRunning.set(true);
  }

  public void pause() {
    isRunning.set(false);
  }

  protected void waitUntilRunningOrDropped() {
    while (!isRunning.get() && !isDropped.get()) {
      LockSupport.parkNanos(CHECK_RUNNING_INTERVAL_NANOS);
    }
  }

  public final synchronized void create() {
    try {
      resume();
      createInternal();
    } catch (final Exception e) {
      LOGGER.warn("Failed to create task", e);
    }
  }

  public abstract void createInternal() throws Exception;

  public final synchronized void start() {
    try {
      resume();
      startInternal();
    } catch (final Exception e) {
      LOGGER.warn("Failed to start task", e);
    }
  }

  public abstract void startInternal() throws Exception;

  public final synchronized void stop() {
    try {
      pause();
      stopInternal();
    } catch (final Exception e) {
      LOGGER.warn("Failed to stop task", e);
    }
  }

  public abstract void stopInternal() throws Exception;

  public final synchronized void drop() {
    try {
      pause();
      isDropped.set(true);
      dropInternal();
    } catch (final Exception e) {
      LOGGER.warn("Failed to drop task", e);
    }
  }

  public abstract void dropInternal() throws Exception;
}
