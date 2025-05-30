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

package org.apache.iotdb.collector.runtime.plugin.load;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@NotThreadSafe
public class PluginClassLoaderManager {

  private final Map<String, PluginClassLoader> pluginNameToClassLoaderMap;

  public PluginClassLoaderManager() {
    this.pluginNameToClassLoaderMap = new ConcurrentHashMap<>();
  }

  public void removePluginClassLoader(final String pluginName) throws IOException {
    final PluginClassLoader classLoader = pluginNameToClassLoaderMap.remove(pluginName);
    if (classLoader != null) {
      classLoader.markAsDeprecated();
    }
  }

  public PluginClassLoader getPluginClassLoader(final String pluginName) throws IOException {
    return pluginNameToClassLoaderMap.get(pluginName.toUpperCase());
  }

  public void addPluginClassLoader(final String pluginName, final PluginClassLoader classLoader) {
    pluginNameToClassLoaderMap.put(pluginName.toUpperCase(), classLoader);
  }

  public PluginClassLoader createPluginClassLoader(final String pluginDirPath) throws IOException {
    return new PluginClassLoader(pluginDirPath);
  }
}
