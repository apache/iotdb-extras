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

import org.apache.iotdb.pipe.api.access.Row;
import org.apache.iotdb.pipe.api.collector.RowCollector;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import org.apache.tsfile.write.record.Tablet;

import java.util.function.BiConsumer;

public class PipeRawTabletInsertionEvent extends PipeInsertionEvent
    implements TabletInsertionEvent, AutoCloseable {

  public PipeRawTabletInsertionEvent(Tablet tablet, String deviceId) {
    this.deviceId = deviceId;
    this.tablet = tablet;
    this.tablet.setDeviceId(deviceId);
  }

  public String deviceId;
  protected Tablet tablet;
  private boolean isAligned;

  @Override
  public Iterable<TabletInsertionEvent> processRowByRow(
      final BiConsumer<Row, RowCollector> consumer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<TabletInsertionEvent> processTablet(
      final BiConsumer<Tablet, RowCollector> consumer) {
    throw new UnsupportedOperationException();
  }

  public Tablet getTablet() {
    return tablet;
  }

  public boolean isAligned() {
    return isAligned;
  }

  public Tablet convertToTablet() {
    return tablet;
  }

  public boolean isTableModelEvent() {
    return false;
  }

  public String getDeviceId() {
    return deviceId;
  }

  public String getTableModelDatabaseName() {
    return null;
  }

  @Override
  public void close() {}
}
