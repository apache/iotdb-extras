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
import org.apache.iotdb.relational.flink.source.cdc.client.IoTDBSubscriptionClient;
import org.apache.iotdb.relational.flink.source.cdc.split.IoTDBSubscriptionSplit;
import org.apache.iotdb.relational.flink.source.common.IoTDBDeserializationSchema;
import org.apache.iotdb.session.subscription.consumer.ISubscriptionTablePullConsumer;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessage;
import org.apache.iotdb.session.subscription.payload.SubscriptionMessageType;
import org.apache.iotdb.session.subscription.payload.SubscriptionRecordHandler.SubscriptionResultSet;

import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.core.io.InputStatus;
import org.apache.tsfile.read.query.dataset.ResultSet;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Source reader of the unbounded CDC source. It polls the IoTDB subscription and emits the received
 * row-level records. The reader never reaches end of input on its own.
 *
 * @param <OUT> source output type
 */
public class IoTDBSubscriptionSourceReader<OUT>
    implements SourceReader<OUT, IoTDBSubscriptionSplit> {

  private final SourceReaderContext context;
  private final IoTDBOptions options;
  private final IoTDBDeserializationSchema<OUT> deserializer;
  private final Deque<IoTDBSubscriptionSplit> pendingSplits = new ArrayDeque<>();
  private final Deque<OUT> buffer = new ArrayDeque<>();

  private IoTDBSubscriptionSplit currentSplit;
  private ISubscriptionTablePullConsumer consumer;
  private boolean noMoreSplits;
  private boolean closed;
  private CompletableFuture<Void> availability = CompletableFuture.completedFuture(null);

  public IoTDBSubscriptionSourceReader(
      SourceReaderContext context,
      IoTDBOptions options,
      IoTDBDeserializationSchema<OUT> deserializer) {
    this.context = context;
    this.options = options;
    this.deserializer = deserializer;
  }

  @Override
  public void start() {
    markUnavailable();
    context.sendSplitRequest();
  }

  @Override
  public InputStatus pollNext(ReaderOutput<OUT> output) throws Exception {
    if (closed) {
      return InputStatus.END_OF_INPUT;
    }

    if (buffer.isEmpty()) {
      if (currentSplit == null) {
        currentSplit = pendingSplits.pollFirst();
        if (currentSplit == null) {
          if (noMoreSplits) {
            return InputStatus.END_OF_INPUT;
          }
          markUnavailable();
          context.sendSplitRequest();
          return InputStatus.NOTHING_AVAILABLE;
        }
        openConsumer();
      }
      fetchBatch();
      if (buffer.isEmpty()) {
        markUnavailable();
        return InputStatus.NOTHING_AVAILABLE;
      }
    }

    output.collect(buffer.pollFirst());
    return InputStatus.MORE_AVAILABLE;
  }

  @Override
  public List<IoTDBSubscriptionSplit> snapshotState(long checkpointId) {
    if (currentSplit == null) {
      return Collections.emptyList();
    }
    return Collections.singletonList(currentSplit);
  }

  @Override
  public void notifyCheckpointComplete(long checkpointId) {
    // Offsets are committed by the consumer when auto-commit is enabled.
  }

  @Override
  public CompletableFuture<Void> isAvailable() {
    return availability;
  }

  @Override
  public void addSplits(List<IoTDBSubscriptionSplit> splits) {
    if (closed || splits == null || splits.isEmpty()) {
      return;
    }
    pendingSplits.addAll(splits);
    markAvailable();
  }

  @Override
  public void notifyNoMoreSplits() {
    noMoreSplits = true;
    markAvailable();
  }

  @Override
  public void close() throws Exception {
    if (closed) {
      return;
    }
    closed = true;
    if (consumer != null) {
      consumer.close();
      consumer = null;
    }
    currentSplit = null;
    pendingSplits.clear();
    buffer.clear();
    markAvailable();
  }

  private void openConsumer() throws Exception {
    IoTDBSubscriptionClient.createTopicIfNotExists(options);
    consumer = IoTDBSubscriptionClient.createPullConsumer(options);
    consumer.open();
    consumer.subscribe(currentSplit.getTopic());
  }

  private void fetchBatch() throws Exception {
    List<SubscriptionMessage> messages =
        consumer.poll(Duration.ofMillis(options.getCdcPollTimeoutMs()));
    for (SubscriptionMessage message : messages) {
      if (message.getMessageType() != SubscriptionMessageType.RECORD_HANDLER.getType()) {
        continue;
      }
      for (ResultSet resultSet : message.getResultSets()) {
        SubscriptionDataIterator iterator =
            new SubscriptionDataIterator((SubscriptionResultSet) resultSet);
        while (iterator.next()) {
          OUT record = deserializer.deserialize(iterator);
          if (record != null) {
            buffer.add(record);
          }
        }
      }
    }
  }

  private synchronized void markUnavailable() {
    if (!availability.isDone()) {
      return;
    }
    availability = new CompletableFuture<>();
  }

  private synchronized void markAvailable() {
    CompletableFuture<Void> current = availability;
    if (!current.isDone()) {
      current.complete(null);
    }
    availability = CompletableFuture.completedFuture(null);
  }
}
