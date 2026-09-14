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

package org.apache.iotdb.relational.flink;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.DynamicTableSourceFactory;

import java.util.Collections;
import java.util.Set;

/**
 * SPI factory of the IoTDB relational (table model) Flink connector.
 *
 * <p>TODO: fill in the factory identifier, option definitions and source/sink creation logic.
 */
public class IoTDBRelationalDynamicTableFactory
    implements DynamicTableSourceFactory, DynamicTableSinkFactory {

  @Override
  public DynamicTableSource createDynamicTableSource(Context context) {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public DynamicTableSink createDynamicTableSink(Context context) {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public String factoryIdentifier() {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public Set<ConfigOption<?>> requiredOptions() {
    return Collections.emptySet();
  }

  @Override
  public Set<ConfigOption<?>> optionalOptions() {
    return Collections.emptySet();
  }
}

