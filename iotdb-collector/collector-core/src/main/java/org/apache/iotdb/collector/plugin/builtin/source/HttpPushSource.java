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

import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.api.event.DemoEvent;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class HttpPushSource extends PushSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpPushSource.class);

  private volatile boolean isStarted = true;
  private Thread workerThread;

  @Override
  public void validate(final PipeParameterValidator validator) {}

  @Override
  public void customize(
      final PipeParameters parameters, final PipeSourceRuntimeConfiguration configuration) {}

  @Override
  public void start() {
    if (workerThread == null || !workerThread.isAlive()) {
      isStarted = true;
      workerThread = new Thread(this::doWork);
      workerThread.start();
    }
  }

  private void doWork() {
    try {
      while (isStarted && !Thread.currentThread().isInterrupted()) {
        final Event event = new DemoEvent(String.valueOf(new Random().nextInt(1000)));
        LOGGER.info("{} created successfully ...", event);
        supply(event);
        TimeUnit.SECONDS.sleep(2);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOGGER.error("Error in push source", e);
    }
  }

  @Override
  public void close() {
    isStarted = false;
    if (workerThread != null) {
      workerThread.interrupt();
      try {
        workerThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      workerThread = null;
    }
  }
}
