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

package org.apache.iotdb.collector.runtime.task.processor;

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorProcessorRuntimeConfiguration;
import org.apache.iotdb.collector.plugin.api.event.PeriodicalEvent;
import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.task.Task;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.event.EventContainer;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.collector.service.ScheduleService;
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

import static org.apache.iotdb.collector.config.TaskRuntimeOptions.TASK_PROCESSOR_RING_BUFFER_SIZE;
import static org.apache.iotdb.collector.config.TaskRuntimeOptions.TASK_PROCESS_PARALLELISM_NUM;

public class ProcessorTask extends Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorTask.class);

  private static final Map<String, ExecutorService> REGISTERED_EXECUTOR_SERVICES =
      new ConcurrentHashMap<>();

  private final Disruptor<EventContainer> disruptor;
  private final EventCollector sinkProducer;
  private ProcessorConsumer[] processorConsumers;

  public ProcessorTask(
      final String taskId,
      final Map<String, String> attributes,
      final EventCollector sinkProducer) {
    super(
        taskId,
        attributes,
        TASK_PROCESS_PARALLELISM_NUM.key(),
        attributes.containsKey(TASK_PROCESS_PARALLELISM_NUM.key())
            ? Integer.parseInt(TASK_PROCESS_PARALLELISM_NUM.key())
            : TASK_PROCESS_PARALLELISM_NUM.value());

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
            TASK_PROCESSOR_RING_BUFFER_SIZE.value(),
            REGISTERED_EXECUTOR_SERVICES.get(taskId),
            ProducerType.MULTI,
            new BlockingWaitStrategy());
    this.sinkProducer = sinkProducer;
  }

  @Override
  public void createInternal() throws Exception {
    final PluginRuntime pluginRuntime =
        RuntimeService.plugin().isPresent() ? RuntimeService.plugin().get() : null;
    if (pluginRuntime == null) {
      throw new IllegalStateException("Plugin runtime is down");
    }

    final long creationTime = System.currentTimeMillis();
    processorConsumers = new ProcessorConsumer[parallelism];
    for (int i = 0; i < parallelism; i++) {
      processorConsumers[i] =
          new ProcessorConsumer(pluginRuntime.constructProcessor(parameters), sinkProducer);
      processorConsumers[i].setDispatch(dispatch);
      try {
        processorConsumers[i].consumer().validate(new PipeParameterValidator(parameters));
        processorConsumers[i]
            .consumer()
            .customize(
                parameters,
                new CollectorProcessorRuntimeConfiguration(taskId, creationTime, parallelism, i));
      } catch (final Exception e) {
        try {
          processorConsumers[i].consumer().close();
        } catch (final Exception ex) {
          LOGGER.warn("Failed to close sink on creation failure", ex);
        }
        throw e;
      }
    }
    disruptor.handleEventsWithWorkerPool(processorConsumers);

    disruptor.setDefaultExceptionHandler(new ProcessorExceptionHandler());

    disruptor.start();

    // Scheduled and proactive sink actions
    ScheduleService.pushEvent()
        .ifPresent(
            event ->
                event.register(
                    taskId,
                    () -> sinkProducer.collect(new PeriodicalEvent()),
                    TaskRuntimeOptions.EXECUTOR_CRON_HEARTBEAT_EVENT_INTERVAL_SECONDS.value()));
  }

  @Override
  public void startInternal() {
    // resume proactive sink actions
    ScheduleService.pushEvent().ifPresent(event -> event.resumeSingleJob(taskId));
  }

  @Override
  public void stopInternal() {
    // pause proactive sink actions
    ScheduleService.pushEvent().ifPresent(event -> event.pauseSingleJob(taskId));
  }

  @Override
  public void dropInternal() {
    if (processorConsumers != null) {
      for (int i = 0; i < parallelism; i++) {
        try {
          processorConsumers[i].consumer().close();
        } catch (final Exception e) {
          LOGGER.warn("Failed to close sink", e);
        }
      }
    }

    // remove proactive sink actions
    ScheduleService.pushEvent().ifPresent(event -> event.deregister(taskId));

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
