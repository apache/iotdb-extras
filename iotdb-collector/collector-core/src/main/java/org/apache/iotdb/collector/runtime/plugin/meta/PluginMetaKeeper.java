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

package org.apache.iotdb.collector.runtime.plugin.meta;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class PluginMetaKeeper {

  protected final Map<String, PluginMeta> pipePluginNameToMetaMap = new ConcurrentHashMap<>();
  protected final Map<String, Class<?>> builtinPipePluginNameToClassMap = new ConcurrentHashMap<>();

  public PluginMetaKeeper() {
    loadBuiltinPlugins();
  }

  private void loadBuiltinPlugins() {
    //    for (final BuiltinPipePlugin builtinPipePlugin : BuiltinPipePlugin.values()) {
    //      final String pipePluginName = builtinPipePlugin.getPipePluginName();
    //      final Class<?> pipePluginClass = builtinPipePlugin.getPipePluginClass();
    //      final String className = builtinPipePlugin.getClassName();
    //
    //      addPipePluginMeta(pipePluginName, new PluginMeta(pipePluginName, className));
    //      addBuiltinPluginClass(pipePluginName, pipePluginClass);
    //      addPipePluginVisibility(
    //          pipePluginName, VisibilityUtils.calculateFromPluginClass(pipePluginClass));
    //    }
  }

  public void addPipePluginMeta(String pluginName, PluginMeta pluginMeta) {
    pipePluginNameToMetaMap.put(pluginName.toUpperCase(), pluginMeta);
  }

  public void removePipePluginMeta(String pluginName) {
    pipePluginNameToMetaMap.remove(pluginName.toUpperCase());
  }

  public PluginMeta getPipePluginMeta(String pluginName) {
    return pipePluginNameToMetaMap.get(pluginName.toUpperCase());
  }

  public Iterable<PluginMeta> getAllPipePluginMeta() {
    return pipePluginNameToMetaMap.values();
  }

  public boolean containsPipePlugin(String pluginName) {
    return pipePluginNameToMetaMap.containsKey(pluginName.toUpperCase());
  }

  private void addBuiltinPluginClass(String pluginName, Class<?> builtinPipePluginClass) {
    builtinPipePluginNameToClassMap.put(pluginName.toUpperCase(), builtinPipePluginClass);
  }

  public Class<?> getBuiltinPluginClass(String pluginName) {
    return builtinPipePluginNameToClassMap.get(pluginName.toUpperCase());
  }

  public String getPluginNameByJarName(String jarName) {
    for (Map.Entry<String, PluginMeta> entry : pipePluginNameToMetaMap.entrySet()) {
      if (jarName.equals(entry.getValue().getJarName())) {
        return entry.getKey();
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PluginMetaKeeper that = (PluginMetaKeeper) o;
    return pipePluginNameToMetaMap.equals(that.pipePluginNameToMetaMap);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pipePluginNameToMetaMap);
  }
}
