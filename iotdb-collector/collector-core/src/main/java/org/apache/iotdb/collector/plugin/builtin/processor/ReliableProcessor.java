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

package org.apache.iotdb.collector.plugin.builtin.processor;

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorRuntimeEnvironment;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeTsFileInsertionEvent;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.collector.EventCollector;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeProcessorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;

import org.apache.commons.io.FileUtils;
import org.apache.tsfile.write.TsFileWriter;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReliableProcessor implements PipeProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReliableProcessor.class);

  private File currentInstanceFile;
  private Tablet lastTablet;
  private Tablet currentTablet;

  private int currentTabletSize = 0;
  private boolean shouldFlush = false;

  private final List<Tablet> tablets = new ArrayList<>();

  @Override
  public void validate(PipeParameterValidator pipeParameterValidator) throws Exception {}

  @Override
  public void customize(
      final PipeParameters pipeParameters,
      final PipeProcessorRuntimeConfiguration pipeProcessorRuntimeConfiguration)
      throws Exception {
    currentInstanceFile =
        new File(
            TaskRuntimeOptions.RELIABLE_FILE_STORAGE_DIR.value()
                + File.separator
                + ((CollectorRuntimeEnvironment)
                        pipeProcessorRuntimeConfiguration.getRuntimeEnvironment())
                    .getInstanceIndex());
  }

  @Override
  public void process(
      final TsFileInsertionEvent fileInsertionEvent, final EventCollector eventCollector)
      throws Exception {
    if (!currentInstanceFile.exists() && !currentInstanceFile.mkdir()) {
      LOGGER.warn("Failed to create directory {}", currentInstanceFile);
    }

    if (!shouldFlushToFile()) {
      tablets.add(currentTablet);
      currentTabletSize += currentTablet.serialize().limit();
    } else {
      final String fileName = System.currentTimeMillis() + ".tsfile";
      final File storeFile = new File(currentInstanceFile, fileName);

      try (final TsFileWriter writer = new TsFileWriter(storeFile)) {
        for (final Tablet tablet : tablets) {
          writer.writeTable(tablet);
        }

        tablets.clear();
        currentTabletSize = 0;
        shouldFlush = false;
      }

      eventCollector.collect(new PipeTsFileInsertionEvent(storeFile));
    }
  }

  @Override
  public void process(
      final TabletInsertionEvent tabletInsertionEvent, final EventCollector eventCollector)
      throws Exception {
    if (tabletInsertionEvent instanceof PipeRawTabletInsertionEvent) {
      final Tablet tablet = ((PipeRawTabletInsertionEvent) tabletInsertionEvent).getTablet();
      currentTablet = tablet;

      if (Objects.isNull(lastTablet)) {
        lastTablet = tablet;
      } else {
        if (!tabletSchemaEquals(lastTablet, currentTablet)) {
          shouldFlush = true;
        }
      }

      process((TsFileInsertionEvent) tabletInsertionEvent, eventCollector);
    }
  }

  @Override
  public void process(final Event event, final EventCollector eventCollector) throws Exception {
    if (event instanceof TabletInsertionEvent) {
      process((TabletInsertionEvent) event, eventCollector);
    }
  }

  private boolean tabletSchemaEquals(final Tablet t1, final Tablet t2) {
    if (!Objects.equals(t1.getDeviceId(), t2.getDeviceId())) {
      return false;
    }

    final List<IMeasurementSchema> s1 = t1.getSchemas();
    final List<IMeasurementSchema> s2 = t2.getSchemas();

    if (s1.size() != s2.size()) {
      return false;
    }

    for (int i = 0; i < s1.size(); i++) {
      if (!s1.get(i).getMeasurementName().equals(s2.get(i).getMeasurementName())) {
        return false;
      }
      if (s1.get(i).getType() != s2.get(i).getType()) {
        return false;
      }
    }
    final List<Tablet.ColumnCategory> c1 = t1.getColumnTypes();
    final List<Tablet.ColumnCategory> c2 = t2.getColumnTypes();

    return Objects.equals(c1, c2);
  }

  private boolean shouldFlushToFile() {
    return shouldFlush || (currentTabletSize >= 1024 * 1024 * 1024 && tablets.size() >= 20);
  }

  @Override
  public void close() throws Exception {
    FileUtils.delete(currentInstanceFile);
    LOGGER.info("ReliableProcessor close, dir path {}", currentInstanceFile.getAbsolutePath());
  }
}
