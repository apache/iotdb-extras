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

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;

import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.table.types.DataType;

import java.io.IOException;

/**
 * Sink v2 entry of the IoTDB relational (table model) Flink connector.
 *
 * <p>The generic input type is used by both APIs:
 *
 * <ul>
 *   <li>Table API/SQL creates {@code IoTDBSink<RowData>}.
 *   <li>DataStream API can create {@code IoTDBSink<IN>} with a user-provided {@link
 *       SinkDataConverter}.
 * </ul>
 *
 * <p>The sink only carries serializable state (options, the physical row {@link DataType} and the
 * converter); the writer owns the IoTDB session and the buffered tablet.
 *
 * @param <IN> input record type
 */
public class IoTDBSink<IN> implements Sink<IN> {

  private static final long serialVersionUID = 1L;

  private final IoTDBOptions options;
  private final DataType physicalRowDataType;
  private final SinkDataConverter<IN> converter;

  public IoTDBSink(
      IoTDBOptions options,
      DataType physicalRowDataType,
      SinkDataConverter<IN> converter) {
    this.options = options;
    this.physicalRowDataType = physicalRowDataType;
    this.converter = converter;
  }

  @Override
  public SinkWriter<IN> createWriter(InitContext context) throws IOException {
    return new IoTDBSinkWriter<>(options, physicalRowDataType, converter);
  }

  public static <IN> Builder<IN> builder() {
    return new Builder<>();
  }

  /** Builder for the DataStream API entry point. */
  public static class Builder<IN> {

    private IoTDBOptions options;
    private DataType physicalRowDataType;
    private SinkDataConverter<IN> converter;

    public Builder<IN> setOptions(IoTDBOptions options) {
      this.options = options;
      return this;
    }

    public Builder<IN> setPhysicalRowDataType(DataType physicalRowDataType) {
      this.physicalRowDataType = physicalRowDataType;
      return this;
    }

    public Builder<IN> setConverter(SinkDataConverter<IN> converter) {
      this.converter = converter;
      return this;
    }

    public IoTDBSink<IN> build() {
      return new IoTDBSink<>(options, physicalRowDataType, converter);
    }
  }
}
