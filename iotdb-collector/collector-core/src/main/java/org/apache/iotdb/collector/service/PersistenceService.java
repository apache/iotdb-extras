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

import org.apache.iotdb.collector.persistence.DBConstant;
import org.apache.iotdb.collector.persistence.PluginPersistence;
import org.apache.iotdb.collector.persistence.TaskPersistence;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.iotdb.collector.config.PluginRuntimeOptions.PLUGIN_INSTALL_LIB_DIR;

public class PersistenceService implements IService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PersistenceService.class);

  private static final AtomicReference<PluginPersistence> PLUGIN = new AtomicReference<>();
  private static final AtomicReference<TaskPersistence> TASK = new AtomicReference<>();

  @Override
  public void start() {
    initPluginDir();

    PLUGIN.set(new PluginPersistence(DBConstant.PLUGIN_DATABASE_URL));
    TASK.set(new TaskPersistence(DBConstant.TASK_DATABASE_URL));

    PLUGIN.get().tryResume();
    TASK.get().tryResume();
  }

  private void initPluginDir() {
    final Path pluginInstallPath = Paths.get(PLUGIN_INSTALL_LIB_DIR.value());
    try {
      if (!Files.exists(pluginInstallPath)) {
        FileUtils.forceMkdir(pluginInstallPath.toFile());
      }
    } catch (final IOException e) {
      LOGGER.warn("Failed to create plugin install directory", e);
    }
  }

  public static Optional<PluginPersistence> plugin() {
    return Optional.of(PLUGIN.get());
  }

  public static Optional<TaskPersistence> task() {
    return Optional.of(TASK.get());
  }

  @Override
  public void stop() {
    PLUGIN.set(null);
    TASK.set(null);
  }

  @Override
  public String name() {
    return "PersistenceService";
  }
}
