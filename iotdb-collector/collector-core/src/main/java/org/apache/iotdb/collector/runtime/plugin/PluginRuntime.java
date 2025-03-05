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

import org.apache.iotdb.collector.runtime.plugin.constructor.ProcessorConstructor;
import org.apache.iotdb.collector.runtime.plugin.constructor.SinkConstructor;
import org.apache.iotdb.collector.runtime.plugin.constructor.SourceConstructor;
import org.apache.iotdb.collector.runtime.plugin.meta.PluginMetaKeeper;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.PipeSink;
import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

public class PluginRuntime implements AutoCloseable {

  private final PluginMetaKeeper metaKeeper;
  private final SourceConstructor sourceConstructor;
  private final ProcessorConstructor processorConstructor;
  private final SinkConstructor sinkConstructor;

  public PluginRuntime() {
    this.metaKeeper = new PluginMetaKeeper();
    this.sourceConstructor = new SourceConstructor(metaKeeper);
    this.processorConstructor = new ProcessorConstructor(metaKeeper);
    this.sinkConstructor = new SinkConstructor(metaKeeper);
  }

  public PipeSource constructSource(final PipeParameters sourceParameters) {
    return sourceConstructor.reflectPlugin(sourceParameters);
  }

  public boolean isPullSource(final PipeParameters sourceParameters) throws Exception {
    try (final PipeSource source = constructSource(sourceParameters)) {
      return sourceConstructor.isPullSource(source);
    }
  }

  public boolean isPushSource(final PipeParameters sourceParameters) throws Exception {
    try (final PipeSource source = constructSource(sourceParameters)) {
      return sourceConstructor.isPushSource(source);
    }
  }

  public PipeProcessor constructProcessor(final PipeParameters processorParameters) {
    return processorConstructor.reflectPlugin(processorParameters);
  }

  public PipeSink constructSink(final PipeParameters sinkParameters) {
    return sinkConstructor.reflectPlugin(sinkParameters);
  }

  public boolean createPlugin() {
    return true;
  }

  public boolean alterPlugin() {
    return true;
  }

  public boolean startPlugin() {
    return true;
  }

  public boolean stopPlugin() {
    return true;
  }

  public boolean dropPlugin() {
    return true;
  }

  @Override
  public void close() throws Exception {}
}
