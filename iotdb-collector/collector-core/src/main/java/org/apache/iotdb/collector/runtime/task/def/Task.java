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

import org.apache.iotdb.collector.runtime.task.datastructure.TaskEventCollector;
import org.apache.iotdb.collector.runtime.task.datastructure.TaskEventConsumer;
import org.apache.iotdb.collector.runtime.task.datastructure.TaskEventConsumerController;
import org.apache.iotdb.pipe.api.PipePlugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.stream.Stream;

public abstract class Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);

  protected TaskEventConsumer[] getConsumer(
      final PipePlugin plugin, final int consumerNum, final TaskEventCollector collector) {
    return Stream.generate(() -> createConsumer(plugin, collector))
        .limit(consumerNum)
        .toArray(TaskEventConsumer[]::new);
  }

  private TaskEventConsumer createConsumer(
      final PipePlugin plugin, final TaskEventCollector collector) {
    return Objects.nonNull(collector)
        ? new TaskEventConsumer(plugin, collector, new TaskEventConsumerController())
        : new TaskEventConsumer(plugin, new TaskEventConsumerController());
  }

  protected <T> T createInstance(final Class<T> clazz) {
    try {
      final Constructor<T> constructor = clazz.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (final NoSuchMethodException e) {
      LOGGER.warn("class {} is abstract class.", clazz, e);
    } catch (final IllegalAccessException e) {
      LOGGER.warn("failed to visit class {} constructor method.", clazz, e);
    } catch (final InstantiationException e) {
      LOGGER.warn("failed to instantiate class {}.", clazz, e);
    } catch (final InvocationTargetException e) {
      LOGGER.warn("the constructor threw an exception.", e);
    }
    throw new RuntimeException();
  }
}
