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

package org.apache.iotdb.relational.flink.source.scan;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.source.common.IoTDBDataIterator;
import org.apache.iotdb.relational.flink.source.common.IoTDBDeserializationSchema;
import org.apache.iotdb.relational.flink.source.scan.split.IoTDBSourceSplit;
import org.apache.iotdb.session.TableSessionBuilder;

import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.table.types.DataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Source reader for the bounded IoTDB table source. */
public class IoTDBSourceReader<OUT> implements SourceReader<OUT, IoTDBSourceSplit> {

  private final SourceReaderContext context;
  private final IoTDBOptions options;
  private final DataType rowDataType;
  private final IoTDBDeserializationSchema<OUT> deserializer;
  private final Deque<IoTDBSourceSplit> pendingSplits = new ArrayDeque<>();

  private IoTDBSourceSplit currentSplit;
  private ITableSession session;
  private SessionDataSet dataSet;
  private IoTDBDataIterator iterator;
  private boolean noMoreSplits;
  private boolean closed;
  private CompletableFuture<Void> availability = CompletableFuture.completedFuture(null);

  public IoTDBSourceReader(
      SourceReaderContext context,
      IoTDBOptions options,
      DataType rowDataType,
      IoTDBDeserializationSchema<OUT> deserializer) {
    this.context = context;
    this.options = options;
    this.rowDataType = rowDataType;
    this.deserializer = deserializer;
  }

  @Override
  public void start() {
    if (pendingSplits.isEmpty() && currentSplit == null && !noMoreSplits) {
      markUnavailable();
      context.sendSplitRequest();
    }
  }

  @Override
  public InputStatus pollNext(ReaderOutput<OUT> output) throws Exception {
    if (closed) {
      return InputStatus.END_OF_INPUT;
    }

    while (true) {
      if (iterator == null && !openNextSplit()) {
        if (noMoreSplits) {
          return InputStatus.END_OF_INPUT;
        }
        markUnavailable();
        context.sendSplitRequest();
        return InputStatus.NOTHING_AVAILABLE;
      }

      if (iterator.next()) {
        OUT record = deserializer.deserialize(iterator);
        if (record != null) {
          output.collect(record);
          return InputStatus.MORE_AVAILABLE;
        }
        continue;
      }

      finishCurrentSplit();
    }
  }

  @Override
  public List<IoTDBSourceSplit> snapshotState(long checkpointId) {
    List<IoTDBSourceSplit> splits = new ArrayList<>(pendingSplits.size() + 1);
    if (currentSplit != null) {
      splits.add(currentSplit);
    }
    splits.addAll(pendingSplits);
    return splits;
  }

  @Override
  public CompletableFuture<Void> isAvailable() {
    return availability;
  }

  @Override
  public void addSplits(List<IoTDBSourceSplit> splits) {
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
    closeCurrentSplit();
    pendingSplits.clear();
    markAvailable();
  }

  private boolean openNextSplit() throws Exception {
    currentSplit = pendingSplits.pollFirst();
    if (currentSplit == null) {
      return false;
    }

    TableSessionBuilder builder =
        new TableSessionBuilder()
            .nodeUrls(options.getNodeUrls())
            .username(options.getUsername())
            .password(options.getPassword());
    if (currentSplit.getDatabase() != null) {
      builder.database(currentSplit.getDatabase());
    }
    try {
      session = builder.build();
      dataSet = session.executeQueryStatement(currentSplit.getSql());
      iterator = new SessionScanDataIterator(dataSet.iterator());
      return true;
    } catch (Exception e) {
      try {
        closeCurrentSplit();
      } catch (Exception closeException) {
        e.addSuppressed(closeException);
      }
      currentSplit = null;
      throw e;
    }
  }

  private void finishCurrentSplit() throws Exception {
    closeCurrentSplit();
    currentSplit = null;
    markAvailable();
  }

  private void closeCurrentSplit() throws Exception {
    Exception failure = null;
    try {
      if (dataSet != null) {
        dataSet.close();
      }
    } catch (Exception e) {
      failure = e;
    } finally {
      dataSet = null;
      iterator = null;
    }

    try {
      if (session != null) {
        session.close();
      }
    } catch (Exception e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    } finally {
      session = null;
    }

    if (failure != null) {
      throw failure;
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
