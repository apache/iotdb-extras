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
import org.apache.iotdb.collector.runtime.task.TaskRuntime;

public class RuntimeService implements IService {

  private static TaskRuntime task;
  private static PluginRuntime plugin;

  @Override
  public void start() {
    plugin = new PluginRuntime();
    task = new TaskRuntime();
  }

  public static TaskRuntime task() {
    return task;
  }

  public static PluginRuntime plugin() {
    return plugin;
  }

  @Override
  public void stop() {
    task = null;
    plugin = null;
  }

  @Override
  public String name() {
    return "RuntimeService";
  }
}
