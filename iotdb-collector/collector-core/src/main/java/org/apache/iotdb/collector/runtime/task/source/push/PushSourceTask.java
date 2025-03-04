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

package org.apache.iotdb.collector.runtime.task.source.push;

import org.apache.iotdb.collector.plugin.source.HttpPushSource;
import org.apache.iotdb.collector.runtime.task.event.EventCollector;
import org.apache.iotdb.collector.runtime.task.processor.ProcessorTask;
import org.apache.iotdb.collector.runtime.task.source.SourceTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class PushSourceTask extends SourceTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(PushSourceTask.class);

  private final EventCollector collector;

  public PushSourceTask(
      final String taskId,
      final Map<String, String> sourceParams,
      final ProcessorTask processorTask) {
    super(taskId, sourceParams, processorTask);

    this.collector = processorTask.makeProducer();
  }

  @Override
  public void createInternal() {
    createSourceTask();
    for (int i = 0; i < sourceParallelismNum; i++) {
      // use sourceAttribute later
      try (final HttpPushSource source = new HttpPushSource(collector)) {
        addSourceTask(source);
        SOURCE_EXECUTOR_SERVICE
            .get(taskId)
            .submit(
                () -> {
                  try {
                    source.start();
                  } catch (final Exception e) {
                    LOGGER.warn("Failed to start push source", e);
                    throw new RuntimeException(e);
                  }
                });
      } catch (Exception e) {
        LOGGER.warn("failed to create instance of HttpSource", e);
      }
    }
  }
}
