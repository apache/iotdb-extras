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

package org.apache.iotdb.collector.runtime.task.def.processor;

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.processor.DoNothingProcessor;
import org.apache.iotdb.collector.runtime.plugin.PluginFactory;
import org.apache.iotdb.collector.runtime.task.def.Task;
import org.apache.iotdb.collector.runtime.task.def.sink.SinkTask;
import org.apache.iotdb.collector.runtime.task.execution.DisruptorTaskExceptionHandler;
import org.apache.iotdb.collector.runtime.task.execution.TaskEventCollector;
import org.apache.iotdb.collector.runtime.task.execution.TaskEventConsumer;
import org.apache.iotdb.collector.runtime.task.execution.TaskEventContainer;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.util.Map;
import java.util.Optional;

public class ProcessorTask extends Task {

  private Disruptor<TaskEventContainer> processorDisruptor;
  private TaskEventConsumer[] eventConsumers;

  private final PipeParameters parameters;
  private final int processParallelismNum;
  private final TaskEventCollector collector;

  public ProcessorTask(final Map<String, String> processorAttributes, final SinkTask sinkTask) {
    this.parameters = new PipeParameters(processorAttributes);
    this.processParallelismNum =
        this.parameters.getIntOrDefault(
            TaskRuntimeOptions.TASK_PROCESS_PARALLELISM_NUM.key(),
            TaskRuntimeOptions.TASK_PROCESS_PARALLELISM_NUM.value());
    this.collector = new TaskEventCollector(sinkTask.getSinkRingBuffer());

    this.initProcessorDisruptor();
  }

  @Override
  public void create() {
    if (this.processorDisruptor == null) {
      this.initProcessorDisruptor();
    }

    this.eventConsumers =
        this.getConsumer(
            PluginFactory.createInstance(DoNothingProcessor.class),
            processParallelismNum,
            collector);

    this.processorDisruptor.setDefaultExceptionHandler(new DisruptorTaskExceptionHandler());
    this.processorDisruptor.handleEventsWithWorkerPool(this.eventConsumers);
    this.processorDisruptor.start();
  }

  private void initProcessorDisruptor() {
    this.processorDisruptor =
        new Disruptor<>(
            TaskEventContainer::new,
            this.parameters.getIntOrDefault(
                TaskRuntimeOptions.TASK_PROCESSOR_RING_BUFFER_SIZE.key(),
                TaskRuntimeOptions.TASK_PROCESSOR_RING_BUFFER_SIZE.value()),
            DaemonThreadFactory.INSTANCE,
            ProducerType.MULTI,
            new BlockingWaitStrategy());
  }

  @Override
  public void start() throws Exception {
    for (final TaskEventConsumer consumer : this.eventConsumers) {
      consumer.getConsumerController().resume();
    }
  }

  @Override
  public void stop() {
    for (final TaskEventConsumer consumer : this.eventConsumers) {
      consumer.getConsumerController().pause();
    }
  }

  @Override
  public void drop() {
    if (this.processorDisruptor != null) {
      this.processorDisruptor.shutdown();
      this.processorDisruptor = null;
    }
  }

  public Optional<RingBuffer<TaskEventContainer>> getProcessorRingBuffer() {
    if (this.processorDisruptor != null) {
      return Optional.of(this.processorDisruptor.getRingBuffer());
    }
    return Optional.empty();
  }
}
