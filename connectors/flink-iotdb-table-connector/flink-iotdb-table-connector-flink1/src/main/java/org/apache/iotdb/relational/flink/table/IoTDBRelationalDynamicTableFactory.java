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
 * <p>The factory only wires options and the dynamic table source/sink. Runtime read/write logic is
 * intentionally not implemented yet.
 */
public class IoTDBRelationalDynamicTableFactory
    implements DynamicTableSourceFactory, DynamicTableSinkFactory {

  @Override
  public DynamicTableSource createDynamicTableSource(Context context) {
    FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
    helper.validate();
    return new IoTDBRelationalDynamicTableSource(
        toOptions(helper.getOptions()), context.getCatalogTable().getResolvedSchema());
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
            IoTDBOptions.LOOKUP_THREAD_SIZE));
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
