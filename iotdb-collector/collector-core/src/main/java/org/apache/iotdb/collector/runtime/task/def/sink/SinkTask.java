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

package org.apache.iotdb.collector.runtime.task.def.sink;

import com.lmax.disruptor.WorkHandler;
import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.sink.SessionSink;
import org.apache.iotdb.collector.runtime.plugin.PluginFactory;
import org.apache.iotdb.collector.runtime.task.def.Task;
import org.apache.iotdb.collector.runtime.task.execution.EventCollector;
import org.apache.iotdb.collector.runtime.task.execution.EventConsumerExceptionHandler;
import org.apache.iotdb.collector.runtime.task.execution.EventConsumer;
import org.apache.iotdb.collector.runtime.task.execution.EventContainer;
import org.apache.iotdb.pipe.api.PipePlugin;
import org.apache.iotdb.pipe.api.PipeSink;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class SinkTask extends Task {

  private static class SinkConsumer implements WorkHandler<EventContainer> {

    private final PipeSink sink;

    private SinkConsumer(final PipeSink sink) {
      this.sink = sink;
    }

    @Override
    public void onEvent(EventContainer event) throws Exception {
      sink.transfer(event.getEvent());
    }
  }

  private final Disruptor<EventContainer> disruptor;
  private final int parallelism;

  public SinkTask(final Map<String, String> processorAttributes) {
    final PipeParameters parameters = new PipeParameters(processorAttributes);
    parallelism =
        parameters.getIntOrDefault(
            TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.key(),
            TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.value());
    disruptor = new Disruptor<>(
        EventContainer::new,
        parallelism,
        DaemonThreadFactory.INSTANCE,
        ProducerType.MULTI,
        new BlockingWaitStrategy());
  }

  @Override
  public void createInternal() {
    disruptor.setDefaultExceptionHandler(new EventConsumerExceptionHandler());
    disruptor.handleEventsWithWorkerPool(Stream.generate(() -> new SinkConsumer(PluginFactory.createInstance(SessionSink.class)))
        .limit(parallelism)
        .toArray(SinkConsumer[]::new));
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
    disruptor.shutdown();
  }

  public RingBuffer<EventContainer> getSinkRingBuffer() {
    return this.disruptor.getRingBuffer();
  }
}
