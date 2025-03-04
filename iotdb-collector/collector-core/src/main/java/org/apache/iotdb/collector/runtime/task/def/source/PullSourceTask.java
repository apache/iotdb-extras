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

package org.apache.iotdb.collector.runtime.task.def.source;

import org.apache.iotdb.collector.plugin.source.HttpPullSource;
import org.apache.iotdb.collector.runtime.plugin.PluginFactory;
import org.apache.iotdb.collector.runtime.task.def.processor.ProcessorTask;
import org.apache.iotdb.pipe.api.event.Event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class PullSourceTask extends SourceTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(PullSourceTask.class);

  public PullSourceTask(
      final String taskId,
      final Map<String, String> sourceParams,
      final ProcessorTask processorTask) {
    super(taskId, sourceParams, processorTask);
  }

  @Override
  public void create() {
    createSourceTask();
    for (int i = 0; i < sourceParallelismNum; i++) {
      // use sourceAttribute later
      try (final HttpPullSource source = PluginFactory.createInstance(HttpPullSource.class)) {
        addSourceTask(source);
        SOURCE_EXECUTOR_SERVICE
            .get(taskId)
            .submit(
                () -> {
                  try {
                    source.start();
                    while (SOURCE_TASK_STATUS.containsKey(taskId)) {
                      if (SOURCE_TASK_STATUS.get(taskId) == TaskState.Stopped) {
                        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                        continue;
                      }

                      final Event event = source.supply();
                      processorTask
                          .getProcessorRingBuffer()
                          .ifPresent(
                              ringBuffer ->
                                  ringBuffer.publishEvent(
                                      ((container, sequence, o) -> container.setEvent(event)),
                                      event));
                    }
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
      } catch (final Exception e) {
        LOGGER.warn("Pull source task failed", e);
      }
    }
  }
}
