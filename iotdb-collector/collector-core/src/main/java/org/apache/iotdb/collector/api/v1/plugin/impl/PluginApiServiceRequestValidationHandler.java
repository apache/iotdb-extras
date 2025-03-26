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

import org.apache.iotdb.collector.api.v1.plugin.model.CreatePluginRequest;
import org.apache.iotdb.collector.api.v1.plugin.model.DropPluginRequest;

import java.util.Objects;

public class PluginApiServiceRequestValidationHandler {
  private PluginApiServiceRequestValidationHandler() {}

  public static void validateCreatePluginRequest(final CreatePluginRequest createPluginRequest) {
    Objects.requireNonNull(createPluginRequest.getPluginName(), "plugin name cannot be null");
    Objects.requireNonNull(createPluginRequest.getClassName(), "class name cannot be null");
    Objects.requireNonNull(createPluginRequest.getJarName(), "jar name cannot be null");
  }

  public static void validateDropPluginRequest(final DropPluginRequest dropPluginRequest) {
    Objects.requireNonNull(dropPluginRequest.getPluginName(), "plugin name cannot be null");
  }
}
