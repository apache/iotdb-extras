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

import java.util.Objects;

public class PluginMeta {

  private final String pluginName;
  private final String className;

  // jarName and jarMD5 are used to identify the jar file.
  // they could be null if the plugin is built-in. they should be both null or both not null.
  private final boolean isBuiltin;
  private final String jarName;
  private final String jarMD5;

  public PluginMeta(
      String pluginName, String className, boolean isBuiltin, String jarName, String jarMD5) {
    this.pluginName = Objects.requireNonNull(pluginName).toUpperCase();
    this.className = Objects.requireNonNull(className);

    this.isBuiltin = isBuiltin;
    if (isBuiltin) {
      this.jarName = jarName;
      this.jarMD5 = jarMD5;
    } else {
      this.jarName = Objects.requireNonNull(jarName);
      this.jarMD5 = Objects.requireNonNull(jarMD5);
    }
  }

  public PluginMeta(String pluginName, String className) {
    this.pluginName = Objects.requireNonNull(pluginName).toUpperCase();
    this.className = Objects.requireNonNull(className);

    this.isBuiltin = true;
    this.jarName = null;
    this.jarMD5 = null;
  }

  public boolean isBuiltin() {
    return isBuiltin;
  }

  public String getPluginName() {
    return pluginName;
  }

  public String getClassName() {
    return className;
  }

  public String getJarName() {
    return jarName;
  }

  public String getJarMD5() {
    return jarMD5;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    PluginMeta that = (PluginMeta) obj;
    return pluginName.equals(that.pluginName)
        && className.equals(that.className)
        && isBuiltin == that.isBuiltin
        && Objects.equals(jarName, that.jarName)
        && Objects.equals(jarMD5, that.jarMD5);
  }

  @Override
  public int hashCode() {
    return pluginName.hashCode();
  }

  @Override
  public String toString() {
    return "PluginMeta{"
        + "pluginName='"
        + pluginName
        + '\''
        + ", className='"
        + className
        + '\''
        + ", isBuiltin="
        + isBuiltin
        + ", jarName='"
        + jarName
        + '\''
        + ", jarMD5='"
        + jarMD5
        + '\''
        + '}';
  }
}
