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

package org.apache.iotdb.collector.api.v1.plugin.impl;

import org.apache.iotdb.collector.api.v1.plugin.PluginApiService;
import org.apache.iotdb.collector.api.v1.plugin.model.CreatePluginRequest;
import org.apache.iotdb.collector.api.v1.plugin.model.DropPluginRequest;
import org.apache.iotdb.collector.service.RuntimeService;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

public class PluginApiServiceImpl extends PluginApiService {

  @Override
  public Response createPlugin(
      final CreatePluginRequest createPluginRequest, final SecurityContext securityContext) {
    PluginApiServiceRequestValidationHandler.validateCreatePluginRequest(createPluginRequest);

    return RuntimeService.plugin().isPresent()
        ? RuntimeService.plugin()
            .get()
            .createPlugin(
                createPluginRequest.getPluginName().toUpperCase(),
                createPluginRequest.getClassName(),
                createPluginRequest.getJarName(),
                null,
                true)
        : Response.ok("create plugin").build();
  }

  @Override
  public Response dropPlugin(
      final DropPluginRequest dropPluginRequest, final SecurityContext securityContext) {
    PluginApiServiceRequestValidationHandler.validateDropPluginRequest(dropPluginRequest);

    return RuntimeService.plugin().isPresent()
        ? RuntimeService.plugin().get().dropPlugin(dropPluginRequest.getPluginName().toUpperCase())
        : Response.ok("drop plugin").build();
  }

  @Override
  public Response showPlugin(final SecurityContext securityContext) {
    return RuntimeService.plugin().isPresent()
        ? RuntimeService.plugin().get().showPlugin()
        : Response.ok("show plugin").build();
  }
}
