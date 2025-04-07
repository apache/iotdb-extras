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

package org.apache.iotdb.collector.plugin.builtin.sink.event;

import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;
import org.apache.iotdb.pipe.api.exception.PipeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class PipeTsFileInsertionEvent extends PipeInsertionEvent implements TsFileInsertionEvent {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeTsFileInsertionEvent.class);

  private File tsFile;

  @Override
  public boolean isTableModelEvent() {
    throw new PipeException("");
  }

  public File getTsFile() {
    return tsFile;
  }

  @Override
  public Iterable<TabletInsertionEvent> toTabletInsertionEvents() throws PipeException {
    return toTabletInsertionEvents(Long.MAX_VALUE);
  }

  public Iterable<TabletInsertionEvent> toTabletInsertionEvents(final long timeoutMs)
      throws PipeException {
    return null;
  }

  @Override
  public void close() throws Exception {}
}
