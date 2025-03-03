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

package org.apache.iotdb.collector.runtime.task.def;

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.sink.SessionSink;
import org.apache.iotdb.collector.runtime.task.datastructure.TaskEventConsumer;
import org.apache.iotdb.collector.runtime.task.datastructure.TaskEventContainer;
import org.apache.iotdb.collector.runtime.task.exception.DisruptorTaskExceptionHandler;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.util.Map;

public class SinkTask extends Task implements TaskComponent {

  private Disruptor<TaskEventContainer> sinkDisruptor;
  private TaskEventConsumer[] eventConsumers;
  private final PipeParameters parameters;
  private final int sinkParallelismNum;

  public SinkTask(final Map<String, String> processorAttributes) {
    this.parameters = new PipeParameters(processorAttributes);
    this.sinkParallelismNum =
        this.parameters.getIntOrDefault(
            TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.key(),
            TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.value());

    this.initSinkDisruptor();
  }

  @Override
  public void create() {
    if (this.sinkDisruptor == null) {
      this.initSinkDisruptor();
    }

    this.eventConsumers =
        this.getConsumer(this.createInstance(SessionSink.class), sinkParallelismNum, null);

    this.sinkDisruptor.setDefaultExceptionHandler(new DisruptorTaskExceptionHandler());
    this.sinkDisruptor.handleEventsWithWorkerPool(this.eventConsumers);
    this.sinkDisruptor.start();
  }

  private void initSinkDisruptor() {
    this.sinkDisruptor =
        new Disruptor<>(
            TaskEventContainer::new,
            this.parameters.getIntOrDefault(
                TaskRuntimeOptions.TASK_SINK_RING_BUFFER_SIZE.key(),
                TaskRuntimeOptions.TASK_SINK_RING_BUFFER_SIZE.value()),
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
    if (this.sinkDisruptor != null) {
      this.sinkDisruptor.shutdown();
      this.sinkDisruptor = null;
    }
  }

  public RingBuffer<TaskEventContainer> getSinkRingBuffer() {
    return this.sinkDisruptor.getRingBuffer();
  }
}
