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

package org.apache.iotdb.collector.runtime.plugin.constructor;

import org.apache.iotdb.collector.runtime.plugin.meta.PluginMeta;
import org.apache.iotdb.collector.runtime.plugin.meta.PluginMetaKeeper;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.pipe.api.PipePlugin;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.exception.PipeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public abstract class PluginConstructor {

  private static final Logger LOGGER = LoggerFactory.getLogger(PluginConstructor.class);

  private final PluginMetaKeeper pluginMetaKeeper;

  protected final Map<String, Supplier<PipePlugin>> pluginConstructors = new HashMap<>();

  protected PluginConstructor(PluginMetaKeeper pluginMetaKeeper) {
    this.pluginMetaKeeper = pluginMetaKeeper;
    initConstructors();
  }

  // New plugins shall be put here
  protected abstract void initConstructors();

  public abstract PipePlugin reflectPlugin(PipeParameters pipeParameters);

  public PipePlugin reflectPluginByKey(String pluginKey) {
    return pluginConstructors.getOrDefault(pluginKey, () -> reflect(pluginKey)).get();
  }

  private PipePlugin reflect(String pluginName) {
    if (pluginMetaKeeper == null) {
      throw new PipeException(
          "Failed to reflect PipePlugin instance, because PluginMetaKeeper is null.");
    }

    if (pluginName == null) {
      throw new PipeException(
          "Failed to reflect PipePlugin instance, because plugin name is null.");
    }

    final PluginMeta information = pluginMetaKeeper.getPipePluginMeta(pluginName);
    if (information == null) {
      String errorMessage =
          String.format(
              "Failed to reflect PipePlugin instance, because "
                  + "PipePlugin %s has not been registered.",
              pluginName.toUpperCase());
      LOGGER.warn(errorMessage);
      throw new PipeException(errorMessage);
    }

    try {
      final Class<?> pluginClass =
          information.isBuiltin()
              ? pluginMetaKeeper.getBuiltinPluginClass(information.getPluginName())
              : Class.forName(
                  information.getClassName(),
                  true,
                  RuntimeService.plugin().isPresent()
                      ? RuntimeService.plugin().get().getClassLoader(pluginName)
                      : null);
      return (PipePlugin) pluginClass.getDeclaredConstructor().newInstance();
    } catch (final Exception e) {
      String errorMessage =
          String.format(
              "Failed to reflect PipePlugin %s(%s) instance, because %s",
              pluginName, information.getClassName(), e);
      LOGGER.warn(errorMessage, e);
      throw new PipeException(errorMessage);
    }
  }
}
