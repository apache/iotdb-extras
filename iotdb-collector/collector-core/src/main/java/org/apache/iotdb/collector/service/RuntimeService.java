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

package org.apache.iotdb.collector.service;

import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.progress.ProgressRuntime;
import org.apache.iotdb.collector.runtime.task.TaskRuntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class RuntimeService implements IService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeService.class);

  private static final AtomicReference<TaskRuntime> TASK = new AtomicReference<>();
  private static final AtomicReference<PluginRuntime> PLUGIN = new AtomicReference<>();
  private static final AtomicReference<ProgressRuntime> PROGRESS = new AtomicReference<>();

  @Override
  public synchronized void start() {
    TASK.set(new TaskRuntime());
    PLUGIN.set(new PluginRuntime());
    PROGRESS.set(new ProgressRuntime());
  }

  public static Optional<TaskRuntime> task() {
    return Optional.of(TASK.get());
  }

  public static Optional<PluginRuntime> plugin() {
    return Optional.of(PLUGIN.get());
  }

  public static Optional<ProgressRuntime> progress() {
    return Optional.of(PROGRESS.get());
  }

  @Override
  public synchronized void stop() {
    task()
        .ifPresent(
            taskRuntime -> {
              try {
                taskRuntime.close();
              } catch (final Exception e) {
                LOGGER.warn("[RuntimeService] Failed to close task runtime: {}", e.getMessage(), e);
              }
            });
    TASK.set(null);

    plugin()
        .ifPresent(
            pluginRuntime -> {
              try {
                pluginRuntime.close();
              } catch (final Exception e) {
                LOGGER.warn(
                    "[RuntimeService] Failed to close plugin runtime: {}", e.getMessage(), e);
              }
            });
    PLUGIN.set(null);

    progress()
        .ifPresent(
            progressRuntime -> {
              try {
                progressRuntime.close();
              } catch (final IOException e) {
                LOGGER.warn(
                    "[RuntimeService] Failed to close progress runtime: {}", e.getMessage(), e);
              }
            });
    PROGRESS.set(null);
  }

  @Override
  public String name() {
    return "RuntimeService";
  }
}
