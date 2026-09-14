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

import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;

import java.io.IOException;

/**
 * Sink v2 entry of the IoTDB relational (table model) Flink connector.
 *
 * <p>The generic input type is used by both APIs:
 *
 * <ul>
 *   <li>Table API/SQL creates {@code IoTDBSink<RowData>}.
 *   <li>DataStream API can create {@code IoTDBSink<IN>} with a user-provided serializer.
 * </ul>
 *
 * <p>TODO: implement batching and {@code ITableSession.insert(Tablet)} in the writer.
 *
 * @param <IN> input record type
 */
public class IoTDBSink<IN> implements Sink<IN> {

  private static final long serialVersionUID = 1L;

  private final IoTDBRelationalOptions options;
  private final IoTDBTabletSerializer<IN> serializer;

  public IoTDBSink(
      IoTDBRelationalOptions options, IoTDBTabletSerializer<IN> serializer) {
    this.options = options;
    this.serializer = serializer;
  }

  @Override
  public SinkWriter<IN> createWriter(InitContext context) throws IOException {
    return new IoTDBSinkWriter<>(options, serializer);
  }

  public static <IN> Builder<IN> builder() {
    return new Builder<>();
  }

  /** Builder for the DataStream API entry point. */
  public static class Builder<IN> {

    private IoTDBRelationalOptions options;
    private IoTDBTabletSerializer<IN> serializer;

    public Builder<IN> setOptions(IoTDBRelationalOptions options) {
      this.options = options;
      return this;
    }

    public Builder<IN> setSerializer(IoTDBTabletSerializer<IN> serializer) {
      this.serializer = serializer;
      return this;
    }

    public IoTDBSink<IN> build() {
      return new IoTDBSink<>(options, serializer);
    }
  }
}
