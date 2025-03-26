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

package org.apache.iotdb.collector.runtime.task.processor;

import org.apache.iotdb.collector.runtime.task.TaskDispatch;
import org.apache.iotdb.collector.runtime.task.event.EventContainer;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.collector.EventCollector;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;

import com.lmax.disruptor.WorkHandler;

class ProcessorConsumer implements WorkHandler<EventContainer> {

  private final PipeProcessor processor;
  private final EventCollector eventCollector;

  private TaskDispatch dispatch;

  ProcessorConsumer(final PipeProcessor processor, final EventCollector eventCollector) {
    this.processor = processor;
    this.eventCollector = eventCollector;
  }

  PipeProcessor consumer() {
    return processor;
  }

  @Override
  public void onEvent(final EventContainer eventContainer) throws Exception {
    dispatch.waitUntilRunningOrDropped();

    // TODO: retry strategy
    final Event event = eventContainer.getEvent();
    if (event instanceof TabletInsertionEvent) {
      processor.process((TabletInsertionEvent) event, eventCollector);
    } else if (event instanceof TsFileInsertionEvent) {
      processor.process((TsFileInsertionEvent) event, eventCollector);
    } else if (event != null) {
      processor.process(event, eventCollector);
    }
  }

  public void setDispatch(final TaskDispatch dispatch) {
    this.dispatch = dispatch;
  }
}
