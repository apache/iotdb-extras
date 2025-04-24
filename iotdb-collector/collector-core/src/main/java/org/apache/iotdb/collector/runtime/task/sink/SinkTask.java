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

package org.apache.iotdb.collector.runtime.task.sink;

import org.apache.iotdb.collector.plugin.api.customizer.CollectorSinkRuntimeConfiguration;
import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.task.Task;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.event.EventContainer;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.iotdb.collector.config.TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM;
import static org.apache.iotdb.collector.config.TaskRuntimeOptions.TASK_SINK_RING_BUFFER_SIZE;

public class SinkTask extends Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(SinkTask.class);

  private static final Map<String, ExecutorService> REGISTERED_EXECUTOR_SERVICES =
      new ConcurrentHashMap<>();

  private final Disruptor<EventContainer> disruptor;
  private SinkConsumer[] consumers;

  public SinkTask(final String taskId, final Map<String, String> attributes) {
    super(taskId, attributes, TASK_SINK_PARALLELISM_NUM.key(), TASK_SINK_PARALLELISM_NUM.value());

    REGISTERED_EXECUTOR_SERVICES.putIfAbsent(
        taskId,
        new ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(parallelism))); // TODO: thread name

    disruptor =
        new Disruptor<>(
            EventContainer::new,
            TASK_SINK_RING_BUFFER_SIZE.value(),
            REGISTERED_EXECUTOR_SERVICES.get(taskId),
            ProducerType.MULTI,
            new BlockingWaitStrategy());
  }

  @Override
  public void createInternal() throws Exception {
    final PluginRuntime pluginRuntime =
        RuntimeService.plugin().isPresent() ? RuntimeService.plugin().get() : null;
    if (pluginRuntime == null) {
      throw new IllegalStateException("Plugin runtime is down");
    }

    final long creationTime = System.currentTimeMillis();
    consumers = new SinkConsumer[parallelism];
    for (int i = 0; i < parallelism; i++) {
      consumers[i] = new SinkConsumer(pluginRuntime.constructSink(parameters));
      consumers[i].setDispatch(dispatch);
      try {
        consumers[i].consumer().validate(new PipeParameterValidator(parameters));
        consumers[i]
            .consumer()
            .customize(
                parameters,
                new CollectorSinkRuntimeConfiguration(taskId, creationTime, parallelism, i));
        consumers[i].consumer().handshake();
      } catch (final Exception e) {
        try {
          consumers[i].consumer().close();
        } catch (final Exception ex) {
          LOGGER.warn("Failed to close sink on creation failure", ex);
        }
        throw e;
      }
    }
    disruptor.handleEventsWithWorkerPool(consumers);

    disruptor.setDefaultExceptionHandler(new SinkExceptionHandler());

    disruptor.start();
  }

  @Override
  public void startInternal() {
    // do nothing
  }

  @Override
  public void stopInternal() {
    // do nothing
  }

  @Override
  public void dropInternal() {
    if (consumers != null) {
      for (int i = 0; i < parallelism; i++) {
        try {
          consumers[i].consumer().close();
        } catch (final Exception e) {
          LOGGER.warn("Failed to close sink", e);
        }
      }
    }

    disruptor.shutdown();

    final ExecutorService executorService = REGISTERED_EXECUTOR_SERVICES.remove(taskId);
    if (executorService != null) {
      executorService.shutdown();
    }
  }

  public EventCollector makeProducer() {
    return new EventCollector(disruptor.getRingBuffer());
  }
}
