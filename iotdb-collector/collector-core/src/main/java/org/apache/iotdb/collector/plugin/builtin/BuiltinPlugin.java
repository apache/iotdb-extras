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

package org.apache.iotdb.collector.plugin.builtin;

import org.apache.iotdb.collector.plugin.builtin.processor.DoNothingProcessor;
import org.apache.iotdb.collector.plugin.builtin.processor.SubscriptionProcessor;
import org.apache.iotdb.collector.plugin.builtin.sink.DemoSink;
import org.apache.iotdb.collector.plugin.builtin.sink.protocol.IoTDBDataRegionSyncConnector;
import org.apache.iotdb.collector.plugin.builtin.source.HttpPullSource;
import org.apache.iotdb.collector.plugin.builtin.source.HttpPushSource;
import org.apache.iotdb.collector.plugin.builtin.source.IoTDBPushSource;

public enum BuiltinPlugin {

  // Push Sources
  HTTP_PUSH_SOURCE("http-push-source", HttpPushSource.class),

  // Pull Sources
  HTTP_PULL_SOURCE("http-pull-source", HttpPullSource.class),
  SUBSCRIPTION_SOURCE("subscription-source", IoTDBPushSource.class),

  // Processors
  DO_NOTHING_PROCESSOR("do-nothing-processor", DoNothingProcessor.class),
  SUBSCRIPTION_PROCESSOR("subscription-processor", SubscriptionProcessor.class),

  // Sinks
  IOTDB_DEMO_SINK("iotdb-demo-sink", DemoSink.class),
  IOTDB_SYNC_SINK("iotdb-sync-sink", IoTDBDataRegionSyncConnector.class);

  private final String collectorPluginName;
  private final Class<?> collectorPluginClass;
  private final String className;

  BuiltinPlugin(final String collectorPluginName, final Class<?> collectorPluginClass) {
    this.collectorPluginName = collectorPluginName;
    this.collectorPluginClass = collectorPluginClass;
    this.className = collectorPluginClass.getName();
  }

  public String getPluginName() {
    return collectorPluginName;
  }

  public Class<?> getPluginClass() {
    return collectorPluginClass;
  }

  public String getClassName() {
    return className;
  }
}
