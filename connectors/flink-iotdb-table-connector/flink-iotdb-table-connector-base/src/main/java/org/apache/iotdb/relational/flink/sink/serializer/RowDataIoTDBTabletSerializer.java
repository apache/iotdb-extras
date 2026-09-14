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

package org.apache.iotdb.relational.flink.sink.serializer;

import org.apache.iotdb.relational.flink.cfg.IoTDBRelationalOptions;

import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.data.RowData;
import org.apache.tsfile.write.record.Tablet;

import java.io.IOException;

/**
 * Table API serializer that converts {@link RowData} into an IoTDB {@link Tablet}.
 *
 * <p>TODO: implement the RowData-to-Tablet conversion and keep the runtime state serializable.
 */
public class RowDataIoTDBTabletSerializer implements IoTDBTabletSerializer<RowData> {

  private static final long serialVersionUID = 1L;

  private final IoTDBRelationalOptions options;
  private final ResolvedSchema schema;

  public RowDataIoTDBTabletSerializer(IoTDBRelationalOptions options, ResolvedSchema schema) {
    this.options = options;
    this.schema = schema;
  }

  @Override
  public Tablet serialize(RowData record) throws IOException {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public IoTDBRelationalOptions getOptions() {
    return options;
  }

  public ResolvedSchema getSchema() {
    return schema;
  }
}
