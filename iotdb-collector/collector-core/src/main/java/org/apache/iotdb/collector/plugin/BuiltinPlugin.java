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

package org.apache.iotdb.collector.plugin;

import org.apache.iotdb.collector.plugin.processor.DoNothingProcessor;
import org.apache.iotdb.collector.plugin.sink.SessionSink;
import org.apache.iotdb.collector.plugin.source.HttpPullSource;
import org.apache.iotdb.collector.plugin.source.HttpPushSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public enum BuiltinPlugin {

  // PushSources
  HTTP_PUSH_SOURCE("http-push-source", HttpPushSource.class),

  // PullSources
  HTTP_PULL_SOURCE("http-pull-source", HttpPullSource.class),

  // Processors
  DO_NOTHING_PROCESSOR("do-nothing-processor", DoNothingProcessor.class),

  // Sinks
  IOTDB_SESSION_SINK("iotdb-session-sink", SessionSink.class);

  private final String collectorPluginName;
  private final Class<?> collectorPluginClass;
  private final String className;

  BuiltinPlugin(final String collectorPluginName, final Class<?> collectorPluginClass) {
    this.collectorPluginName = collectorPluginName;
    this.collectorPluginClass = collectorPluginClass;
    this.className = collectorPluginClass.getName();
  }

  public String getCollectorPluginName() {
    return collectorPluginName;
  }

  public Class<?> getCollectorPluginClass() {
    return collectorPluginClass;
  }

  public String getClassName() {
    return className;
  }

  public static final Set<String> SHOW_COLLECTOR_PLUGINS_BLACKLIST =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  // PushSources
                  HTTP_PUSH_SOURCE.getCollectorPluginName().toUpperCase(),
                  // PullSources
                  HTTP_PULL_SOURCE.getCollectorPluginName().toUpperCase(),
                  // Processors
                  DO_NOTHING_PROCESSOR.getCollectorPluginName().toUpperCase(),
                  // Sinks
                  IOTDB_SESSION_SINK.getCollectorPluginName().toUpperCase())));
}
