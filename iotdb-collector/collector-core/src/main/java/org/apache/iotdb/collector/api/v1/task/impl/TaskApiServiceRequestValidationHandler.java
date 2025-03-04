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

package org.apache.iotdb.collector.api.v1.task.impl;

import org.apache.iotdb.collector.api.v1.task.model.CreateTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.DropTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.StartTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.StopTaskRequest;

import java.util.Objects;

public class TaskApiServiceRequestValidationHandler {
  private TaskApiServiceRequestValidationHandler() {}

  public static void validateCreateRequest(final CreateTaskRequest createTaskRequest) {
    Objects.requireNonNull(createTaskRequest.getTaskId(), "taskId cannot be null");
  }

  public static void validateStopRequest(final StopTaskRequest stopTaskRequest) {
    Objects.requireNonNull(stopTaskRequest.getTaskId(), "taskId cannot be null");
  }

  public static void validateStartRequest(final StartTaskRequest startTaskRequest) {
    Objects.requireNonNull(startTaskRequest.getTaskId(), "taskId cannot be null");
  }

  public static void validateDropRequest(final DropTaskRequest dropTaskRequest) {
    Objects.requireNonNull(dropTaskRequest.getTaskId(), "taskId cannot be null");
  }
}
