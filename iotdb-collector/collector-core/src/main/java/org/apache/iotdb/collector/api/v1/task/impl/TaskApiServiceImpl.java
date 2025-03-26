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

import org.apache.iotdb.collector.api.v1.task.TaskApiService;
import org.apache.iotdb.collector.api.v1.task.model.AlterTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.CreateTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.DropTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.StartTaskRequest;
import org.apache.iotdb.collector.api.v1.task.model.StopTaskRequest;
import org.apache.iotdb.collector.runtime.task.TaskStateEnum;
import org.apache.iotdb.collector.service.RuntimeService;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

public class TaskApiServiceImpl extends TaskApiService {

  @Override
  public Response createTask(
      final CreateTaskRequest createTaskRequest, final SecurityContext securityContext) {
    TaskApiServiceRequestValidationHandler.validateCreateRequest(createTaskRequest);

    return RuntimeService.task().isPresent()
        ? RuntimeService.task()
            .get()
            .createTask(
                createTaskRequest.getTaskId(),
                TaskStateEnum.RUNNING,
                createTaskRequest.getSourceAttribute(),
                createTaskRequest.getProcessorAttribute(),
                createTaskRequest.getSinkAttribute(),
                true)
        : Response.serverError().entity("Task runtime is down").build();
  }

  @Override
  public Response alterTask(
      final AlterTaskRequest alterTaskRequest, final SecurityContext securityContext) {
    return Response.ok("alter task").build();
  }

  @Override
  public Response startTask(
      final StartTaskRequest startTaskRequest, final SecurityContext securityContext) {
    TaskApiServiceRequestValidationHandler.validateStartRequest(startTaskRequest);

    return RuntimeService.task().isPresent()
        ? RuntimeService.task().get().startTask(startTaskRequest.getTaskId())
        : Response.serverError().entity("Task runtime is down").build();
  }

  @Override
  public Response stopTask(
      final StopTaskRequest stopTaskRequest, final SecurityContext securityContext) {
    TaskApiServiceRequestValidationHandler.validateStopRequest(stopTaskRequest);

    return RuntimeService.task().isPresent()
        ? RuntimeService.task().get().stopTask(stopTaskRequest.getTaskId())
        : Response.serverError().entity("Task runtime is down").build();
  }

  @Override
  public Response dropTask(
      final DropTaskRequest dropTaskRequest, final SecurityContext securityContext) {
    TaskApiServiceRequestValidationHandler.validateDropRequest(dropTaskRequest);

    return RuntimeService.task().isPresent()
        ? RuntimeService.task().get().dropTask(dropTaskRequest.getTaskId())
        : Response.serverError().entity("Task runtime is down").build();
  }
}
