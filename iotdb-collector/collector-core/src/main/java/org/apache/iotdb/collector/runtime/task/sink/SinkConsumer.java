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

package org.apache.iotdb.collector.runtime.task.sink;

import org.apache.iotdb.collector.runtime.task.TaskDispatch;
import org.apache.iotdb.collector.runtime.task.event.EventContainer;
import org.apache.iotdb.pipe.api.PipeSink;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;

import com.lmax.disruptor.WorkHandler;

class SinkConsumer implements WorkHandler<EventContainer> {

  private final PipeSink sink;

  private TaskDispatch dispatch;

  SinkConsumer(final PipeSink sink) {
    this.sink = sink;
  }

  PipeSink consumer() {
    return sink;
  }

  @Override
  public void onEvent(EventContainer eventContainer) throws Exception {
    dispatch.waitUntilRunningOrDropped();

    // TODO: retry strategy
    final Event event = eventContainer.getEvent();
    if (event instanceof TabletInsertionEvent) {
      sink.transfer((TabletInsertionEvent) event);
    } else if (event instanceof TsFileInsertionEvent) {
      sink.transfer((TsFileInsertionEvent) event);
    } else if (event != null) {
      sink.transfer(event);
    }
  }

  public void setDispatch(final TaskDispatch dispatch) {
    this.dispatch = dispatch;
  }
}
