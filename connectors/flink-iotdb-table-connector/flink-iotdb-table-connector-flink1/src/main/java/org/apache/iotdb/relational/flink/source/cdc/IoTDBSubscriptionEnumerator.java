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
import org.apache.iotdb.relational.flink.source.cdc.split.IoTDBSubscriptionSplit;

import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Split enumerator for the unbounded CDC source. It owns a single subscription split and never
 * signals "no more splits", so the source keeps running.
 */
public class IoTDBSubscriptionEnumerator
    implements SplitEnumerator<IoTDBSubscriptionSplit, IoTDBSubscriptionEnumeratorState> {

  private final SplitEnumeratorContext<IoTDBSubscriptionSplit> context;
  private final IoTDBSubscriptionSplit split;
  private final Deque<IoTDBSubscriptionSplit> pendingSplits = new ArrayDeque<>();
  private final Deque<Integer> readersAwaitingSplit = new ArrayDeque<>();
  private final Set<Integer> assignedReaders = new HashSet<>();

  private boolean allSplitsCreated;
  private boolean closed;

  public IoTDBSubscriptionEnumerator(
      SplitEnumeratorContext<IoTDBSubscriptionSplit> context, IoTDBOptions options) {
    this(context, options, null);
  }

  public IoTDBSubscriptionEnumerator(
      SplitEnumeratorContext<IoTDBSubscriptionSplit> context,
      IoTDBOptions options,
      @Nullable IoTDBSubscriptionEnumeratorState checkpoint) {
    this.context = context;
    this.split = new IoTDBSubscriptionSplit(options.getCdcTopic(), options.getCdcConsumerGroup());
    if (checkpoint != null) {
      pendingSplits.addAll(checkpoint.getRemainingSplits());
      allSplitsCreated = checkpoint.isAllSplitsCreated();
    }
  }

  @Override
  public void start() {
    if (!allSplitsCreated) {
      pendingSplits.add(split);
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
  public void addSplitsBack(List<IoTDBSubscriptionSplit> splits, int subtaskId) {
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
  public IoTDBSubscriptionEnumeratorState snapshotState(long checkpointId) {
    return new IoTDBSubscriptionEnumeratorState(allSplitsCreated, new ArrayList<>(pendingSplits));
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
      IoTDBSubscriptionSplit nextSplit = pendingSplits.pollFirst();
      assignedReaders.add(subtaskId);
      context.assignSplit(nextSplit, subtaskId);
    }
    // Never signal "no more splits": the CDC source is unbounded.
  }
}
