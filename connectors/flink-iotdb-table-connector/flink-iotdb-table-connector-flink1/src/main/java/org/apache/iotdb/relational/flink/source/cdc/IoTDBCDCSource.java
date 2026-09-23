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

package org.apache.iotdb.relational.flink.source.cdc;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.source.cdc.enumerator.IoTDBSubscriptionEnumeratorState;
import org.apache.iotdb.relational.flink.source.cdc.enumerator.IoTDBSubscriptionEnumeratorStateSerializer;
import org.apache.iotdb.relational.flink.source.cdc.split.IoTDBSubscriptionSplit;
import org.apache.iotdb.relational.flink.source.cdc.split.IoTDBSubscriptionSplitSerializer;
import org.apache.iotdb.relational.flink.source.common.IoTDBDeserializationSchema;
import org.apache.iotdb.relational.flink.source.scan.IoTDBSource;

import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.table.types.DataType;

/**
 * Unbounded CDC source backed by the IoTDB subscription API.
 *
 * <p>Unlike the bounded {@link IoTDBSource}, this source never finishes on its own.
 *
 * @param <OUT> source output type
 */
public class IoTDBCDCSource<OUT>
    implements Source<OUT, IoTDBSubscriptionSplit, IoTDBSubscriptionEnumeratorState> {

  private static final long serialVersionUID = 1L;

  private final IoTDBOptions options;
  private final DataType projectedRowType;
  private final IoTDBDeserializationSchema<OUT> deserializer;

  public IoTDBCDCSource(
      IoTDBOptions options,
      DataType projectedRowType,
      IoTDBDeserializationSchema<OUT> deserializer) {
    this.options = options;
    this.projectedRowType = projectedRowType;
    this.deserializer = deserializer;
  }

  @Override
  public Boundedness getBoundedness() {
    return Boundedness.CONTINUOUS_UNBOUNDED;
  }

  @Override
  public SourceReader<OUT, IoTDBSubscriptionSplit> createReader(SourceReaderContext readerContext) {
    return new IoTDBSubscriptionSourceReader<>(
        readerContext, options, projectedRowType, deserializer);
  }

  @Override
  public SplitEnumerator<IoTDBSubscriptionSplit, IoTDBSubscriptionEnumeratorState> createEnumerator(
      SplitEnumeratorContext<IoTDBSubscriptionSplit> enumContext) {
    return new IoTDBSubscriptionEnumerator(enumContext, options);
  }

  @Override
  public SplitEnumerator<IoTDBSubscriptionSplit, IoTDBSubscriptionEnumeratorState>
      restoreEnumerator(
          SplitEnumeratorContext<IoTDBSubscriptionSplit> enumContext,
          IoTDBSubscriptionEnumeratorState checkpoint) {
    return new IoTDBSubscriptionEnumerator(enumContext, options, checkpoint);
  }

  @Override
  public SimpleVersionedSerializer<IoTDBSubscriptionSplit> getSplitSerializer() {
    return new IoTDBSubscriptionSplitSerializer();
  }

  @Override
  public SimpleVersionedSerializer<IoTDBSubscriptionEnumeratorState>
      getEnumeratorCheckpointSerializer() {
    return new IoTDBSubscriptionEnumeratorStateSerializer();
  }
}
