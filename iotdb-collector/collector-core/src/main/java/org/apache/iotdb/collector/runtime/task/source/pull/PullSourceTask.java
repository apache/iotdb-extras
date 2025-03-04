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

package org.apache.iotdb.collector.runtime.task.source.pull;

import org.apache.iotdb.collector.plugin.api.customizer.CollectorSourceRuntimeConfiguration;
import org.apache.iotdb.collector.plugin.source.HttpPullSource;
import org.apache.iotdb.collector.runtime.plugin.PluginFactory;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.processor.ProcessorTask;
import org.apache.iotdb.collector.runtime.task.source.SourceTask;
import org.apache.iotdb.commons.concurrent.IoTThreadFactory;
import org.apache.iotdb.commons.concurrent.threadpool.WrappedThreadPoolExecutor;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class PullSourceTask extends SourceTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(PullSourceTask.class);

  private static final Map<String, ExecutorService> REGISTERED_EXECUTOR_SERVICES =
      new ConcurrentHashMap<>();

  private final EventCollector processorProducer;
  private PullSourceConsumer[] consumers;

  public PullSourceTask(
      final String taskId,
      final Map<String, String> sourceParams,
      final ProcessorTask processorTask) {
    super(taskId, sourceParams, processorTask);
    processorProducer = processorTask.makeProducer();
  }

  @Override
  public void createInternal() throws Exception {
    REGISTERED_EXECUTOR_SERVICES.putIfAbsent(
        taskId,
        new WrappedThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(parallelism),
            new IoTThreadFactory(taskId), // TODO: thread name
            taskId));

    final long creationTime = System.currentTimeMillis();
    consumers = new PullSourceConsumer[parallelism];
    for (int i = 0; i < parallelism; i++) {
      consumers[i] =
          new PullSourceConsumer(
              PluginFactory.createInstance(HttpPullSource.class), processorProducer);
      consumers[i].consumer().validate(new PipeParameterValidator(parameters));
      consumers[i]
          .consumer()
          .customize(
              parameters,
              new CollectorSourceRuntimeConfiguration(taskId, creationTime, parallelism, i));
      consumers[i].consumer().start();

      int finalI = i;
      REGISTERED_EXECUTOR_SERVICES
          .get(taskId)
          .submit(
              () -> {
                while (!isDropped.get()) {
                  try {
                    consumers[finalI].onScheduler();
                  } catch (final Exception e) {
                    LOGGER.warn("Failed to pull source", e);
                  }

                  waitUntilRunningOrDropped();
                }
              });
    }
  }

  @Override
  public void startInternal() throws Exception {
    // do nothing
  }

  @Override
  public void stopInternal() throws Exception {
    // do nothing
  }

  @Override
  public void dropInternal() throws Exception {
    final ExecutorService executorService = REGISTERED_EXECUTOR_SERVICES.remove(taskId);
    if (executorService != null) {
      executorService.shutdown();
    }
  }
}
