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

import org.apache.iotdb.relational.flink.cfg.IoTDBRelationalOptions;

import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;

/**
 * Dynamic table source of the IoTDB relational (table model) Flink connector, covering scan and
 * lookup reading.
 *
 * <p>Mirrors the structure of Doris' {@code DorisDynamicTableSource}. TODO: implement the scan and
 * lookup runtime providers.
 */
public class IoTDBRelationalDynamicTableSource implements ScanTableSource, LookupTableSource {

  private final IoTDBRelationalOptions options;
  private final ResolvedSchema schema;

  public IoTDBRelationalDynamicTableSource(IoTDBRelationalOptions options, ResolvedSchema schema) {
    this.options = options;
    this.schema = schema;
  }

  @Override
  public ChangelogMode getChangelogMode() {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public ScanRuntimeProvider getScanRuntimeProvider(ScanContext scanContext) {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public LookupRuntimeProvider getLookupRuntimeProvider(LookupContext lookupContext) {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public DynamicTableSource copy() {
    return new IoTDBRelationalDynamicTableSource(options, schema);
  }

  @Override
  public String asSummaryString() {
    return "IoTDB Relational Dynamic Table Source";
  }
}
