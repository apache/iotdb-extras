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

import org.apache.iotdb.collector.plugin.builtin.source.event.common.PipeRow;
import org.apache.iotdb.collector.plugin.builtin.source.event.common.PipeRowCollector;
import org.apache.iotdb.pipe.api.access.Row;
import org.apache.iotdb.pipe.api.collector.RowCollector;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.function.BiConsumer;

public class PipeRawTabletInsertionEvent extends PipeInsertionEvent
    implements TabletInsertionEvent, AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeRawTabletInsertionEvent.class);

  protected Tablet tablet;
  private final boolean isAligned;

  private final boolean isTableMod;

  public PipeRawTabletInsertionEvent(final Tablet tablet, final boolean isAligned) {
    this.tablet = tablet;
    this.isAligned = isAligned;

    this.isTableMod = tablet.getTableName() != null;
  }

  @Override
  public Iterable<TabletInsertionEvent> processRowByRow(
      final BiConsumer<Row, RowCollector> consumer) {
    if (isTableMod) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.warn("TablePatternParser does not support row by row processing");
      }

      return Collections.emptyList();
    }

    if (tablet.getValues().length == 0 || tablet.getTimestamps().length == 0) {
      return Collections.emptyList();
    }

    final IMeasurementSchema[] schemas = tablet.getSchemas().toArray(new IMeasurementSchema[0]);
    final String[] columnNames = new String[schemas.length];
    final TSDataType[] valueColumnTypes = new TSDataType[schemas.length];
    for (int i = 0; i < schemas.length; i++) {
      columnNames[i] = schemas[i].getMeasurementName();
      valueColumnTypes[i] = schemas[i].getType();
    }

    final PipeRowCollector rowCollector = new PipeRowCollector();
    for (int i = 0; i < tablet.getTimestamps().length; i++) {
      consumer.accept(
          new PipeRow(
              i,
              tablet.getDeviceId(),
              isAligned,
              schemas,
              tablet.getTimestamps(),
              valueColumnTypes,
              tablet.getValues(),
              tablet.getBitMaps(),
              columnNames),
          rowCollector);
    }

    return rowCollector.convertToTabletInsertionEvents();
  }

  @Override
  public Iterable<TabletInsertionEvent> processTablet(
      final BiConsumer<Tablet, RowCollector> consumer) {
    if (isTableMod) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.warn("TablePatternParser does not support tablet processing");
      }

      return Collections.emptyList();
    }

    final PipeRowCollector rowCollector = new PipeRowCollector();
    consumer.accept(convertToTablet(), rowCollector);
    return rowCollector.convertToTabletInsertionEvents();
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

  public String getDeviceId() {
    return tablet.getDeviceId();
  }

  @Override
  public void close() {}
}
