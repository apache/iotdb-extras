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

import org.apache.iotdb.collector.plugin.builtin.BuiltinPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class PluginMetaKeeper {

  private final Map<String, PluginMeta> pipePluginNameToMetaMap = new ConcurrentHashMap<>();
  private final Map<String, Class<?>> builtinPipePluginNameToClassMap = new ConcurrentHashMap<>();
  private final Map<String, String> jarNameToMd5Map = new ConcurrentHashMap<>();
  private final Map<String, Integer> jarNameToReferenceCountMap = new ConcurrentHashMap<>();

  public PluginMetaKeeper() {
    loadBuiltinPlugins();
  }

  private void loadBuiltinPlugins() {
    for (final BuiltinPlugin builtinPlugin : BuiltinPlugin.values()) {
      final String pluginName = builtinPlugin.getPluginName();
      final Class<?> pluginClass = builtinPlugin.getPluginClass();
      final String className = builtinPlugin.getClassName();

      addPipePluginMeta(pluginName, new PluginMeta(pluginName, className));
      addBuiltinPluginClass(pluginName, pluginClass);
    }
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

  public boolean containsJar(final String jarName) {
    return jarNameToMd5Map.containsKey(jarName);
  }

  public boolean jarNameExistsAndMatchesMd5(final String jarName, final String md5) {
    return jarNameToMd5Map.containsKey(jarName) && jarNameToMd5Map.get(jarName).equals(md5);
  }

  public void addJarNameAndMd5(final String jarName, final String md5) {
    if (jarNameToReferenceCountMap.containsKey(jarName)) {
      jarNameToReferenceCountMap.put(jarName, jarNameToReferenceCountMap.get(jarName) + 1);
    } else {
      jarNameToReferenceCountMap.put(jarName, 1);
      jarNameToMd5Map.put(jarName, md5);
    }
  }

  public void removeJarNameAndMd5IfPossible(final String jarName) {
    if (jarNameToReferenceCountMap.containsKey(jarName)) {
      final int count = jarNameToReferenceCountMap.get(jarName);
      if (count == 1) {
        jarNameToReferenceCountMap.remove(jarName);
        jarNameToMd5Map.remove(jarName);
      } else {
        jarNameToReferenceCountMap.put(jarName, count - 1);
      }
    }
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
