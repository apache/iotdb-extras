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

import org.apache.iotdb.collector.plugin.api.customizer.CollectorProcessorRuntimeConfiguration;
import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.task.Task;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.event.EventContainer;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ProcessorTask extends Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorTask.class);

  private final Disruptor<EventContainer> disruptor;
  private final EventCollector sinkProducer;
  private ProcessorConsumer[] processorConsumers;

  public ProcessorTask(
      final String taskId,
      final Map<String, String> attributes,
      final EventCollector sinkProducer) {
    super(taskId, attributes);

    disruptor =
        new Disruptor<>(
            EventContainer::new,
            parallelism, // fault usage
            DaemonThreadFactory.INSTANCE, // fault usage
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
  }

  @Override
  public void startInternal() {
    disruptor.start();
  }

  @Override
  public void stopInternal() {
    disruptor.halt();
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

    disruptor.shutdown();
  }

  public EventCollector makeProducer() {
    return new EventCollector(disruptor.getRingBuffer());
  }
}
