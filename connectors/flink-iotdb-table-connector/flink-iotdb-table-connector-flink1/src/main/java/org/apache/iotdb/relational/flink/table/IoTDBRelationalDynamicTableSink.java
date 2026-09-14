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
import org.apache.iotdb.relational.flink.sink.IoTDBSink;
import org.apache.iotdb.relational.flink.sink.serializer.IoTDBTabletSerializer;
import org.apache.iotdb.relational.flink.sink.serializer.RowDataIoTDBTabletSerializer;

import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkV2Provider;
import org.apache.flink.table.data.RowData;

/**
 * Dynamic table sink of the IoTDB relational (table model) Flink connector.
 *
 * <p>Mirrors the structure of Doris' {@code DorisDynamicTableSink}: the Table layer only wraps the
 * DataStream Sink v2 implementation through {@link SinkV2Provider}.
 */
public class IoTDBRelationalDynamicTableSink implements DynamicTableSink {

  private final IoTDBRelationalOptions options;
  private final ResolvedSchema schema;

  public IoTDBRelationalDynamicTableSink(IoTDBRelationalOptions options, ResolvedSchema schema) {
    this.options = options;
    this.schema = schema;
  }

  @Override
  public ChangelogMode getChangelogMode(ChangelogMode requestedMode) {
    return ChangelogMode.insertOnly();
  }

  @Override
  public SinkRuntimeProvider getSinkRuntimeProvider(Context context) {
    IoTDBTabletSerializer<RowData> serializer =
        new RowDataIoTDBTabletSerializer(options, schema);
    return SinkV2Provider.of(new IoTDBSink<>(options, serializer));
  }

  @Override
  public DynamicTableSink copy() {
    return new IoTDBRelationalDynamicTableSink(options, schema);
  }

  @Override
  public String asSummaryString() {
    return "IoTDB Relational Dynamic Table Sink";
  }
}
