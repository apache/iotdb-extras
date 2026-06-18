/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.metricscrape;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.metricscrape.MetricScrapeConfig.GlobalConfig;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.TableSessionBuilder;

import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MetricTableWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetricTableWriter.class);

  private final GlobalConfig config;
  private final ITableSession session;

  public MetricTableWriter(GlobalConfig config) throws IoTDBConnectionException {
    this.config = config;
    this.session =
        new TableSessionBuilder()
            .nodeUrls(config.getNodeUrls())
            .username(config.getUsername())
            .password(config.getPassword())
            .database(config.getDatabaseName())
            .build();
  }

  public synchronized void initializeDatabase()
      throws IoTDBConnectionException, StatementExecutionException {
    session.executeNonQueryStatement(
        "CREATE DATABASE IF NOT EXISTS "
            + IdentifierUtils.quoteSqlIdentifier(config.getDatabaseName()));
  }

  public synchronized void write(List<PrometheusSample> samples)
      throws IoTDBConnectionException, StatementExecutionException {
    if (samples.isEmpty()) {
      return;
    }
    Map<TabletKey, TabletBuilder> builders = new LinkedHashMap<>();
    for (PrometheusSample sample : samples) {
      TabletKey key = TabletKey.from(sample);
      TabletBuilder builder =
          builders.computeIfAbsent(
              key, ignored -> new TabletBuilder(key, config.getWriteBatchSize()));
      builder.add(sample);
      if (builder.isFull()) {
        flush(builder);
        builder.reset();
      }
    }
    for (TabletBuilder builder : builders.values()) {
      if (!builder.isEmpty()) {
        flush(builder);
      }
    }
  }

  public void close() {
    try {
      session.close();
    } catch (IoTDBConnectionException e) {
      LOGGER.warn("Failed to close IoTDB session.", e);
    }
  }

  private void flush(TabletBuilder builder)
      throws IoTDBConnectionException, StatementExecutionException {
    session.insert(builder.tablet);
  }

  private static class TabletKey {
    private final String table;
    private final String field;
    private final List<String> labelKeys;
    private final Map<String, String> sanitizedToOriginalLabel;

    private static TabletKey from(PrometheusSample sample) {
      Map<String, String> sanitizedToOriginalLabel = sanitizeLabels(sample);
      List<String> labelKeys = new ArrayList<>(sanitizedToOriginalLabel.keySet());
      return new TabletKey(
          IdentifierUtils.sanitize(sample.getMetricFamilyName()),
          IdentifierUtils.sanitize(sample.getMetricName()),
          labelKeys,
          sanitizedToOriginalLabel);
    }

    private static Map<String, String> sanitizeLabels(PrometheusSample sample) {
      List<String> originalLabels = new ArrayList<>(sample.getLabels().keySet());
      Collections.sort(originalLabels);
      Map<String, String> result = new LinkedHashMap<>();
      for (String originalLabel : originalLabels) {
        String sanitizedLabel = IdentifierUtils.sanitize(originalLabel);
        String previous = result.put(sanitizedLabel, originalLabel);
        if (previous != null && !previous.equals(originalLabel)) {
          throw new IllegalArgumentException(
              "Prometheus labels "
                  + previous
                  + " and "
                  + originalLabel
                  + " map to the same IoTDB column "
                  + sanitizedLabel);
        }
      }
      return result;
    }

    private TabletKey(
        String table,
        String field,
        List<String> labelKeys,
        Map<String, String> sanitizedToOriginalLabel) {
      this.table = table;
      this.field = field;
      this.labelKeys = Collections.unmodifiableList(new ArrayList<>(labelKeys));
      this.sanitizedToOriginalLabel =
          Collections.unmodifiableMap(new LinkedHashMap<>(sanitizedToOriginalLabel));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TabletKey)) {
        return false;
      }
      TabletKey tabletKey = (TabletKey) o;
      return Objects.equals(table, tabletKey.table)
          && Objects.equals(field, tabletKey.field)
          && Objects.equals(labelKeys, tabletKey.labelKeys);
    }

    @Override
    public int hashCode() {
      return Objects.hash(table, field, labelKeys);
    }
  }

  private static class TabletBuilder {
    private final TabletKey key;
    private final Tablet tablet;

    private TabletBuilder(TabletKey key, int maxRows) {
      this.key = key;
      List<String> columnNames = new ArrayList<>(key.labelKeys);
      columnNames.add(key.field);
      List<TSDataType> dataTypes = new ArrayList<>(columnNames.size());
      List<ColumnCategory> columnCategories = new ArrayList<>(columnNames.size());
      for (int i = 0; i < key.labelKeys.size(); i++) {
        dataTypes.add(TSDataType.STRING);
        columnCategories.add(ColumnCategory.TAG);
      }
      dataTypes.add(TSDataType.DOUBLE);
      columnCategories.add(ColumnCategory.FIELD);
      this.tablet = new Tablet(key.table, columnNames, dataTypes, columnCategories, maxRows);
    }

    private boolean isFull() {
      return tablet.getRowSize() == tablet.getMaxRowNumber();
    }

    private boolean isEmpty() {
      return tablet.getRowSize() == 0;
    }

    private void reset() {
      tablet.reset();
    }

    private void add(PrometheusSample sample) {
      int row = tablet.getRowSize();
      tablet.addTimestamp(row, sample.getTimestamp());
      for (String labelKey : key.labelKeys) {
        String originalLabel = key.sanitizedToOriginalLabel.get(labelKey);
        tablet.addValue(labelKey, row, sample.getLabels().get(originalLabel));
      }
      tablet.addValue(key.field, row, sample.getValue());
    }
  }
}
