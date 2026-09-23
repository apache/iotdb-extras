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

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.source.scan.enumerator.IoTDBSourceEnumeratorState;
import org.apache.iotdb.relational.flink.source.scan.pushdown.AggregateSpec;
import org.apache.iotdb.relational.flink.source.scan.split.IoTDBSourceSplit;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;

import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.table.types.DataType;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Split enumerator for the bounded IoTDB table source. */
public class IoTDBSourceEnumerator
    implements SplitEnumerator<IoTDBSourceSplit, IoTDBSourceEnumeratorState> {

  private final SplitEnumeratorContext<IoTDBSourceSplit> context;
  private final IoTDBOptions options;
  private final DataType rowDataType;
  private final List<String> filterQueries;
  private final long limit;
  private final AggregateSpec aggregateSpec;
  private final Deque<IoTDBSourceSplit> pendingSplits = new ArrayDeque<>();
  private final Deque<Integer> readersAwaitingSplit = new ArrayDeque<>();
  private final Set<Integer> assignedReaders = new HashSet<>();

  private boolean allSplitsCreated;
  private boolean closed;

  public IoTDBSourceEnumerator(
      SplitEnumeratorContext<IoTDBSourceSplit> context,
      IoTDBOptions options,
      DataType rowDataType,
      List<String> filterQueries,
      long limit,
      AggregateSpec aggregateSpec) {
    this(context, options, rowDataType, filterQueries, limit, aggregateSpec, null);
  }

  public IoTDBSourceEnumerator(
      SplitEnumeratorContext<IoTDBSourceSplit> context,
      IoTDBOptions options,
      DataType rowDataType,
      List<String> filterQueries,
      long limit,
      AggregateSpec aggregateSpec,
      @Nullable IoTDBSourceEnumeratorState checkpoint) {
    this.context = context;
    this.options = options;
    this.rowDataType = rowDataType;
    this.filterQueries = filterQueries == null ? new ArrayList<>() : new ArrayList<>(filterQueries);
    this.limit = limit;
    this.aggregateSpec = aggregateSpec;
    if (checkpoint != null) {
      pendingSplits.addAll(checkpoint.getRemainingSplits());
      allSplitsCreated = true;
    }
  }

  @Override
  public void start() {
    if (!allSplitsCreated) {
      pendingSplits.add(createSingleSplit());
      allSplitsCreated = true;
    }
    assignPendingSplits();
  }

  @Override
  public void handleSplitRequest(int subtaskId, @Nullable String requesterHostname) {
    if (closed) {
      return;
    }
    assignedReaders.remove(subtaskId);
    readersAwaitingSplit.addLast(subtaskId);
    assignPendingSplits();
  }

  @Override
  public void addSplitsBack(List<IoTDBSourceSplit> splits, int subtaskId) {
    assignedReaders.remove(subtaskId);
    for (int i = splits.size() - 1; i >= 0; i--) {
      pendingSplits.addFirst(splits.get(i));
    }
    assignPendingSplits();
  }

  @Override
  public void addReader(int subtaskId) {
    // Splits are assigned when the reader explicitly requests one.
  }

  @Override
  public IoTDBSourceEnumeratorState snapshotState(long checkpointId) {
    return new IoTDBSourceEnumeratorState(new ArrayList<>(pendingSplits));
  }

  @Override
  public void close() throws IOException {
    closed = true;
    pendingSplits.clear();
    readersAwaitingSplit.clear();
    assignedReaders.clear();
  }

  private void assignPendingSplits() {
    while (!pendingSplits.isEmpty() && !readersAwaitingSplit.isEmpty()) {
      int subtaskId = readersAwaitingSplit.pollFirst();
      IoTDBSourceSplit split = pendingSplits.pollFirst();
      assignedReaders.add(subtaskId);
      context.assignSplit(split, subtaskId);
    }

    if (allSplitsCreated && pendingSplits.isEmpty() && assignedReaders.isEmpty()) {
      while (!readersAwaitingSplit.isEmpty()) {
        context.signalNoMoreSplits(readersAwaitingSplit.pollFirst());
      }
    }
  }

  private IoTDBSourceSplit createSingleSplit() {
    String splitId = UUID.randomUUID().toString();
    return new IoTDBSourceSplit(splitId, options.getDatabase(), options.getTable(), buildSql());
  }

  private String buildSql() {
    if (aggregateSpec != null) {
      return IoTDBUtils.buildAggregateQuery(
          options.getTable(),
          aggregateSpec.getSelectExpressions(),
          filterQueries,
          aggregateSpec.getGroupByExpressions());
    }
    return IoTDBUtils.buildSelectQuery(options.getTable(), rowDataType, filterQueries, limit);
  }
}
