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

package org.apache.iotdb.collector.runtime.plugin.constructor;

import org.apache.iotdb.collector.plugin.builtin.BuiltinPlugin;
import org.apache.iotdb.collector.plugin.builtin.sink.protocol.DemoSink;
import org.apache.iotdb.collector.runtime.plugin.meta.PluginMetaKeeper;
import org.apache.iotdb.pipe.api.PipeSink;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

public class SinkConstructor extends PluginConstructor {

  public SinkConstructor(PluginMetaKeeper pluginMetaKeeper) {
    super(pluginMetaKeeper);
  }

  @Override
  protected void initConstructors() {
    pluginConstructors.put(BuiltinPlugin.IOTDB_THRIFT_SINK.getPluginName(), DemoSink::new);
  }

  @Override
  public final PipeSink reflectPlugin(PipeParameters sinkParameters) {
    if (sinkParameters.hasAttribute("sink")) {
      throw new IllegalArgumentException("sink attribute is required");
    }

    return (PipeSink)
        reflectPluginByKey(
            sinkParameters
                .getStringOrDefault("sink", BuiltinPlugin.IOTDB_THRIFT_SINK.getPluginName())
                .toLowerCase());
  }
}
