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

package org.apache.iotdb.collector.plugin.api.customizer;

import org.apache.iotdb.pipe.api.customizer.configuration.PipeRuntimeEnvironment;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;

public class CollectorSourceRuntimeConfiguration implements PipeSourceRuntimeConfiguration {

  private final CollectorRuntimeEnvironment runtimeEnvironment;

  public CollectorSourceRuntimeConfiguration(
      final String pipeName,
      final long creationTime,
      final int parallelism,
      final int instanceIndex) {
    runtimeEnvironment =
        new CollectorRuntimeEnvironment(pipeName, creationTime, parallelism, instanceIndex);
  }

  @Override
  public PipeRuntimeEnvironment getRuntimeEnvironment() {
    return runtimeEnvironment;
  }
}
