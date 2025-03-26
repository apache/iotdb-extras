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

import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;

import java.util.Map;

public abstract class Task {

  protected final String taskId;
  protected final CollectorParameters parameters;

  protected final int parallelism;

  protected final TaskDispatch dispatch;

  protected Task(
      final String taskId,
      final Map<String, String> attributes,
      final String parallelismKey,
      final int parallelismValue) {
    this.taskId = taskId;
    this.parameters = new CollectorParameters(attributes);

    this.parallelism = parameters.getIntOrDefault(parallelismKey, parallelismValue);

    this.dispatch = new TaskDispatch();
  }

  public final synchronized void create() throws Exception {
    dispatch.resume();
    createInternal();
  }

  public abstract void createInternal() throws Exception;

  public final synchronized void start() throws Exception {
    dispatch.resume();
    startInternal();
  }

  public abstract void startInternal() throws Exception;

  public final synchronized void stop() throws Exception {
    dispatch.pause();
    stopInternal();
  }

  public abstract void stopInternal() throws Exception;

  public final synchronized void drop() throws Exception {
    dispatch.remove();
    dropInternal();
  }

  public abstract void dropInternal() throws Exception;
}
