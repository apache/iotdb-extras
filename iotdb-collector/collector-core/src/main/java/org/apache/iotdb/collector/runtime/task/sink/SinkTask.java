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
import org.apache.iotdb.collector.plugin.sink.SessionSink;
import org.apache.iotdb.collector.runtime.plugin.PluginFactory;
import org.apache.iotdb.collector.runtime.task.Task;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.event.EventContainer;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SinkTask extends Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(SinkTask.class);

  private final Disruptor<EventContainer> disruptor;
  private SinkConsumer[] consumers;

  public SinkTask(final String taskId, final Map<String, String> attributes) {
    super(taskId, attributes);

    disruptor =
        new Disruptor<>(
            EventContainer::new,
            parallelism, // fault usage
            DaemonThreadFactory.INSTANCE, // fault usage
            ProducerType.MULTI,
            new BlockingWaitStrategy());
  }

  @Override
  public void createInternal() throws Exception {
    final long creationTime = System.currentTimeMillis();
    consumers = new SinkConsumer[parallelism];
    for (int i = 0; i < parallelism; i++) {
      // TODO: PluginFactory
      consumers[i] = new SinkConsumer(PluginFactory.createInstance(SessionSink.class));
      consumers[i].consumer().validate(new PipeParameterValidator(parameters));
      consumers[i]
          .consumer()
          .customize(
              parameters,
              new CollectorSinkRuntimeConfiguration(taskId, creationTime, parallelism, i));
      consumers[i].consumer().handshake();
    }
    disruptor.handleEventsWithWorkerPool(consumers);

    disruptor.setDefaultExceptionHandler(new SinkExceptionHandler());
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
  }

  public EventCollector makeProducer() {
    return new EventCollector(disruptor.getRingBuffer());
  }
}
