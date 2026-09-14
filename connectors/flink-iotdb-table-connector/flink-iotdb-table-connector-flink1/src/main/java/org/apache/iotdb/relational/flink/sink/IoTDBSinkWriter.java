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

package org.apache.iotdb.relational.flink.sink;

import org.apache.iotdb.relational.flink.cfg.IoTDBRelationalOptions;
import org.apache.iotdb.relational.flink.sink.serializer.IoTDBTabletSerializer;

import org.apache.flink.api.connector.sink2.SinkWriter;

import java.io.IOException;

/**
 * Sink writer of the IoTDB relational (table model) Flink connector.
 *
 * <p>TODO: open one {@code ITableSession}, buffer serialized tablets, flush through {@code
 * ITableSession.insert(Tablet)}, and close all resources.
 *
 * @param <IN> input record type
 */
public class IoTDBSinkWriter<IN> implements SinkWriter<IN> {

  private final IoTDBRelationalOptions options;
  private final IoTDBTabletSerializer<IN> serializer;

  public IoTDBSinkWriter(
      IoTDBRelationalOptions options, IoTDBTabletSerializer<IN> serializer) {
    this.options = options;
    this.serializer = serializer;
  }

  @Override
  public void write(IN element, Context context) throws IOException, InterruptedException {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public void flush(boolean endOfInput) throws IOException, InterruptedException {
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  @Override
  public void close() throws Exception {
    // TODO: flush remaining rows and close the IoTDB session.
  }
}
