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

package org.apache.iotdb.collector.plugin.api;

import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.collector.EventCollector;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeExtractorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;

public abstract class PushSource implements PipeSource {

  protected EventCollector collector;

  public PushSource() {
    this.collector = null;
  }

  @Override
  public final void customize(
      PipeParameters pipeParameters,
      PipeExtractorRuntimeConfiguration pipeExtractorRuntimeConfiguration)
      throws Exception {
    throw new UnsupportedOperationException();
  }

  public final void setCollector(final EventCollector collector) {
    this.collector = collector;
  }

  @Override
  public final Event supply() {
    throw new UnsupportedOperationException();
  }

  public final void supply(final Event event) throws Exception {
    collector.collect(event);
  }
}
