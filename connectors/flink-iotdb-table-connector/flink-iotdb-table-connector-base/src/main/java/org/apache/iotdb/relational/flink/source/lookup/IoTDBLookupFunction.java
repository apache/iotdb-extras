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

package org.apache.iotdb.relational.flink.source.lookup;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.types.DataType;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/** Synchronous lookup function that queries an IoTDB table-model table by the join keys. */
public class IoTDBLookupFunction extends LookupFunction {

  private static final long serialVersionUID = 1L;

  private final IoTDBOptions options;
  private final DataType rowDataType;
  private final int[] keyIndices;

  private transient IoTDBLookupReader reader;

  public IoTDBLookupFunction(IoTDBOptions options, DataType rowDataType, int[] keyIndices) {
    this.options = options;
    this.rowDataType = rowDataType;
    this.keyIndices = keyIndices.clone();
  }

  @Override
  public void open(FunctionContext context) {
    reader = new IoTDBLookupReader(options, rowDataType, keyIndices);
    reader.open();
  }

  @Override
  public Collection<RowData> lookup(RowData keyRow) throws IOException {
    if (keyRow == null) {
      return Collections.emptyList();
    }
    return reader.get(keyRow);
  }

  @Override
  public void close() {
    if (reader != null) {
      reader.close();
    }
  }
}
