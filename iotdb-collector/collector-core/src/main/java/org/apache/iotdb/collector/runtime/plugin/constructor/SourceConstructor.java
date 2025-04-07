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

import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.builtin.BuiltinPlugin;
import org.apache.iotdb.collector.plugin.builtin.source.HttpPullSource;
import org.apache.iotdb.collector.plugin.builtin.source.HttpPushSource;
import org.apache.iotdb.collector.runtime.plugin.meta.PluginMetaKeeper;
import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

public class SourceConstructor extends PluginConstructor {

  public SourceConstructor(PluginMetaKeeper pluginMetaKeeper) {
    super(pluginMetaKeeper);
  }

  @Override
  protected void initConstructors() {
    pluginConstructors.put(BuiltinPlugin.HTTP_PULL_SOURCE.getPluginName(), HttpPullSource::new);
    pluginConstructors.put(BuiltinPlugin.HTTP_PUSH_SOURCE.getPluginName(), HttpPushSource::new);
  }

  @Override
  public final PipeSource reflectPlugin(PipeParameters sourceParameters) {
    if (!sourceParameters.hasAttribute("source")) {
      throw new IllegalArgumentException("source attribute is required");
    }

    return (PipeSource) reflectPluginByKey(sourceParameters.getString("source").toLowerCase());
  }

  public boolean isPullSource(PipeSource source) {
    return source instanceof PullSource;
  }

  public boolean isPushSource(PipeSource source) {
    return source instanceof PushSource;
  }
}
