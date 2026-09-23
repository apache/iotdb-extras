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

package org.apache.iotdb.relational.flink.table;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.lookup.LookupOptions;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SPI factory of the IoTDB relational (table model) Flink connector.
 *
 * <p>The factory resolves the table options and creates the dynamic table source/sink. The source
 * supports bounded scan, lookup and CDC reads, and the sink writes insert rows into an IoTDB table.
 */
public class IoTDBRelationalDynamicTableFactory
    implements DynamicTableSourceFactory, DynamicTableSinkFactory {

  @Override
  public DynamicTableSource createDynamicTableSource(Context context) {
    FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
    helper.validate();
    ReadableConfig config = helper.getOptions();
    return new IoTDBRelationalDynamicTableSource(
        toOptions(config), context.getCatalogTable().getResolvedSchema(), config);
  }

  @Override
  public DynamicTableSink createDynamicTableSink(Context context) {
    FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
    helper.validate();
    return new IoTDBRelationalDynamicTableSink(
        toOptions(helper.getOptions()), context.getCatalogTable().getResolvedSchema());
  }

  @Override
  public String factoryIdentifier() {
    return IoTDBOptions.IDENTIFIER;
  }

  @Override
  public Set<ConfigOption<?>> requiredOptions() {
    return new HashSet<>(
        Arrays.asList(IoTDBOptions.NODE_URLS, IoTDBOptions.DATABASE, IoTDBOptions.TABLE));
  }

  @Override
  public Set<ConfigOption<?>> optionalOptions() {
    return new HashSet<>(
        Arrays.asList(
            IoTDBOptions.USER,
            IoTDBOptions.PASSWORD,
            IoTDBOptions.TIME_COLUMN,
            IoTDBOptions.TAG_COLUMNS,
            IoTDBOptions.ATTRIBUTE_COLUMNS,
            IoTDBOptions.LOOKUP_ASYNC,
            IoTDBOptions.LOOKUP_THREAD_SIZE,
            IoTDBOptions.SCAN_MODE,
            IoTDBOptions.CDC_TOPIC,
            IoTDBOptions.CDC_CONSUMER_GROUP,
            IoTDBOptions.CDC_MODE,
            IoTDBOptions.CDC_START_TIME,
            IoTDBOptions.CDC_POLL_TIMEOUT_MS,
            IoTDBOptions.CDC_AUTO_COMMIT,
            LookupOptions.CACHE_TYPE,
            LookupOptions.PARTIAL_CACHE_MAX_ROWS,
            LookupOptions.PARTIAL_CACHE_EXPIRE_AFTER_WRITE,
            LookupOptions.PARTIAL_CACHE_EXPIRE_AFTER_ACCESS,
            LookupOptions.PARTIAL_CACHE_CACHE_MISSING_KEY));
  }

  private static IoTDBOptions toOptions(ReadableConfig config) {
    return IoTDBOptions.builder()
        .withNodeUrls(Arrays.asList(config.get(IoTDBOptions.NODE_URLS).split(",")))
        .withUsername(config.get(IoTDBOptions.USER))
        .withPassword(config.get(IoTDBOptions.PASSWORD))
        .withDatabase(config.get(IoTDBOptions.DATABASE))
        .withTable(config.get(IoTDBOptions.TABLE))
        .withTimeColumn(config.get(IoTDBOptions.TIME_COLUMN))
        .withTagColumns(parseColumnNames(config.get(IoTDBOptions.TAG_COLUMNS)))
        .withAttributeColumns(parseColumnNames(config.get(IoTDBOptions.ATTRIBUTE_COLUMNS)))
        .withLookupAsync(config.get(IoTDBOptions.LOOKUP_ASYNC))
        .withLookupThreadSize(config.get(IoTDBOptions.LOOKUP_THREAD_SIZE))
        .withScanMode(config.get(IoTDBOptions.SCAN_MODE))
        .withCdcTopic(config.get(IoTDBOptions.CDC_TOPIC))
        .withCdcConsumerGroup(config.get(IoTDBOptions.CDC_CONSUMER_GROUP))
        .withCdcMode(config.get(IoTDBOptions.CDC_MODE))
        .withCdcStartTime(config.get(IoTDBOptions.CDC_START_TIME))
        .withCdcPollTimeoutMs(config.get(IoTDBOptions.CDC_POLL_TIMEOUT_MS))
        .withCdcAutoCommit(config.get(IoTDBOptions.CDC_AUTO_COMMIT))
        .build();
  }

  private static List<String> parseColumnNames(String value) {
    if (value == null || value.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<String> columnNames = new ArrayList<>();
    for (String columnName : value.split(",")) {
      String trimmedColumnName = columnName.trim();
      if (!trimmedColumnName.isEmpty()) {
        columnNames.add(trimmedColumnName);
      }
    }
    return columnNames;
  }
}
