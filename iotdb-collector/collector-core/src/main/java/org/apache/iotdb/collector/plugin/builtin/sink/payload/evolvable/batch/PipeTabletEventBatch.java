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

package org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.batch;

import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class PipeTabletEventBatch implements AutoCloseable {

  protected final List<PipeRawTabletInsertionEvent> events = new ArrayList<>();

  private final int maxDelayInMs;
  private long firstEventProcessingTime = Long.MIN_VALUE;

  protected long totalBufferSize = 0;

  protected volatile boolean isClosed = false;

  protected PipeTabletEventBatch(final int maxDelayInMs, final long requestMaxBatchSizeInBytes) {
    this.maxDelayInMs = maxDelayInMs;
  }

  /**
   * Try offer {@link Event} into batch if the given {@link Event} is not duplicated.
   *
   * @param event the given {@link Event}
   * @return {@code true} if the batch can be transferred
   */
  public synchronized boolean onEvent(final TabletInsertionEvent event) throws IOException {
    if (isClosed || !(event instanceof PipeRawTabletInsertionEvent)) {
      return false;
    }

    // The deduplication logic here is to avoid the accumulation of
    // the same event in a batch when retrying.
    if (events.isEmpty() || !Objects.equals(events.get(events.size() - 1), event)) {
      if (constructBatch(event)) {
        events.add((PipeRawTabletInsertionEvent) event);
      }

      if (firstEventProcessingTime == Long.MIN_VALUE) {
        firstEventProcessingTime = System.currentTimeMillis();
      }
    }

    return shouldEmit();
  }

  /**
   * Added an {@link TabletInsertionEvent} into batch.
   *
   * @param event the {@link TabletInsertionEvent} in batch
   * @return {@code true} if the event is calculated into batch, {@code false} if the event is
   *     cached and not emitted in this batch. If there are failure encountered, just throw
   *     exceptions and do not return {@code false} here.
   */
  protected abstract boolean constructBatch(final TabletInsertionEvent event) throws IOException;

  public boolean shouldEmit() {
    return totalBufferSize >= getMaxBatchSizeInBytes()
        || System.currentTimeMillis() - firstEventProcessingTime >= maxDelayInMs;
  }

  private long getMaxBatchSizeInBytes() {
    // return allocatedMemoryBlock.getMemoryUsageInBytes();
    return 16777216;
  }

  public synchronized void onSuccess() {
    events.clear();

    totalBufferSize = 0;

    firstEventProcessingTime = Long.MIN_VALUE;
  }

  @Override
  public synchronized void close() {
    isClosed = true;

    events.clear();
  }

  public List<PipeRawTabletInsertionEvent> deepCopyEvents() {
    return new ArrayList<>(events);
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }
}
