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

package org.apache.iotdb.collector.runtime.plugin;

import org.apache.iotdb.collector.plugin.BuiltinPlugin;
import org.apache.iotdb.collector.plugin.processor.DoNothingProcessor;
import org.apache.iotdb.collector.plugin.sink.SessionSink;
import org.apache.iotdb.collector.plugin.source.HttpPullSource;
import org.apache.iotdb.pipe.api.PipePlugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class PluginFactory {
  private static final Logger LOGGER = LoggerFactory.getLogger(PluginFactory.class);

  protected final Map<String, Supplier<PipePlugin>> pluginConstructors = new HashMap<>();

  public PluginFactory() {
    initFactory();
  }

  private void initFactory() {
    pluginConstructors.put(
        BuiltinPlugin.HTTP_PULL_SOURCE.getCollectorPluginName(), HttpPullSource::new);
    pluginConstructors.put(
        BuiltinPlugin.DO_NOTHING_PROCESSOR.getCollectorPluginName(), DoNothingProcessor::new);
    pluginConstructors.put(
        BuiltinPlugin.IOTDB_SESSION_SINK.getCollectorPluginName(), SessionSink::new);
    LOGGER.info("builtin plugin has been initialized");
  }
}
