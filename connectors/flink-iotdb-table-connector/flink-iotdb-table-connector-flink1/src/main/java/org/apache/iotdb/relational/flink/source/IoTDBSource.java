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

package org.apache.iotdb.relational.flink.source;

import org.apache.iotdb.relational.flink.cfg.IoTDBRelationalOptions;
import org.apache.iotdb.relational.flink.source.deserializer.IoTDBDeserializationSchema;
import org.apache.iotdb.relational.flink.source.enumerator.IoTDBSourceEnumeratorState;
import org.apache.iotdb.relational.flink.source.enumerator.IoTDBSourceEnumeratorStateSerializer;
import org.apache.iotdb.relational.flink.source.split.IoTDBSourceSplit;
import org.apache.iotdb.relational.flink.source.split.IoTDBSourceSplitSerializer;

import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded FLIP-27 source for reading an IoTDB table-model table.
 *
 * <p>The first implementation uses a single split and emits insert-only rows.
 *
 * @param <OUT> source output type
 */
public class IoTDBSource<OUT> implements Source<OUT, IoTDBSourceSplit, IoTDBSourceEnumeratorState> {

  private static final long serialVersionUID = 1L;

  private final IoTDBRelationalOptions options;
  private final DataType rowDataType;
  private final IoTDBDeserializationSchema<OUT> deserializer;
  private final List<String> filterQueries;
  private final long limit;

  public IoTDBSource(
      IoTDBRelationalOptions options,
      DataType rowDataType,
      IoTDBDeserializationSchema<OUT> deserializer,
      List<String> filterQueries) {
    this(options, rowDataType, deserializer, filterQueries, -1L);
  }

  public IoTDBSource(
      IoTDBRelationalOptions options,
      DataType rowDataType,
      IoTDBDeserializationSchema<OUT> deserializer,
      List<String> filterQueries,
      long limit) {
    this.options = options;
    this.rowDataType = rowDataType;
    this.deserializer = deserializer;
    this.filterQueries = filterQueries == null ? new ArrayList<>() : new ArrayList<>(filterQueries);
    this.limit = limit;
  }

  @Override
  public Boundedness getBoundedness() {
    return Boundedness.BOUNDED;
  }

  @Override
  public SourceReader<OUT, IoTDBSourceSplit> createReader(SourceReaderContext readerContext) {
    return new IoTDBSourceReader<>(readerContext, options, rowDataType, deserializer);
  }

  @Override
  public SplitEnumerator<IoTDBSourceSplit, IoTDBSourceEnumeratorState> createEnumerator(
      SplitEnumeratorContext<IoTDBSourceSplit> enumContext) {
    return new IoTDBSourceEnumerator(enumContext, options, rowDataType, filterQueries, limit);
  }

  @Override
  public SplitEnumerator<IoTDBSourceSplit, IoTDBSourceEnumeratorState> restoreEnumerator(
      SplitEnumeratorContext<IoTDBSourceSplit> enumContext, IoTDBSourceEnumeratorState checkpoint) {
    return new IoTDBSourceEnumerator(
        enumContext, options, rowDataType, filterQueries, limit, checkpoint);
  }

  @Override
  public SimpleVersionedSerializer<IoTDBSourceSplit> getSplitSerializer() {
    return new IoTDBSourceSplitSerializer();
  }

  @Override
  public SimpleVersionedSerializer<IoTDBSourceEnumeratorState> getEnumeratorCheckpointSerializer() {
    return new IoTDBSourceEnumeratorStateSerializer();
  }
}
