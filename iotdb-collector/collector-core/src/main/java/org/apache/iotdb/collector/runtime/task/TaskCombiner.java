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

package org.apache.iotdb.collector.runtime.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskCombiner {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskCombiner.class);

  private final Task source;
  private final Task processor;
  private final Task sink;

  public TaskCombiner(final Task source, final Task processor, final Task sink) {
    this.source = source;
    this.processor = processor;
    this.sink = sink;
  }

  public void create() throws Exception {
    sink.create();
    processor.create();
    source.create();
  }

  public void start() throws Exception {
    sink.start();
    processor.start();
    source.start();
  }

  public void stop() throws Exception {
    source.stop();
    processor.stop();
    sink.stop();
  }

  public void drop() throws Exception {
    stop();

    source.drop();
    processor.drop();
    sink.drop();
  }
}
