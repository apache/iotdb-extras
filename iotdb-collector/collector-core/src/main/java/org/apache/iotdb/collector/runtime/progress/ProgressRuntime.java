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

package org.apache.iotdb.collector.runtime.progress;

import org.apache.iotdb.collector.runtime.task.event.ProgressReportEvent;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProgressRuntime implements Closeable {
  private static final Map<String, ProgressReportEvent> TASKS_PROGRESS = new ConcurrentHashMap<>();

  public void addTaskProgress(final String taskId, final ProgressReportEvent event) {
    TASKS_PROGRESS.putIfAbsent(taskId, event);
  }

  public ProgressIndex getInstanceProgressIndex(final String taskId, final Integer instanceIndex) {
    return TASKS_PROGRESS.get(taskId).getInstanceProgress(instanceIndex);
  }

  public void removeTaskProgress(final String taskId) {
    TASKS_PROGRESS.remove(taskId);
  }

  @Override
  public void close() throws IOException {
    TASKS_PROGRESS.clear();
  }
}
