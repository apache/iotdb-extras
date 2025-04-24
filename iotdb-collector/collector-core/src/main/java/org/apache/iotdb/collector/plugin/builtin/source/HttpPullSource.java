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

package org.apache.iotdb.collector.plugin.builtin.source;

import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.collector.plugin.api.event.DemoEvent;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class HttpPullSource extends PullSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpPullSource.class);

  @Override
  public void validate(PipeParameterValidator pipeParameterValidator) {}

  @Override
  public void customize(
      PipeParameters pipeParameters,
      PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration) {}

  @Override
  public void start() throws Exception {}

  @Override
  public Event supply() {
    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
    final Event event = new DemoEvent(String.valueOf(new Random().nextInt(1000)));
    LOGGER.info("{} created successfully ...", event);
    return event;
  }

  @Override
  public void close() {}
}
