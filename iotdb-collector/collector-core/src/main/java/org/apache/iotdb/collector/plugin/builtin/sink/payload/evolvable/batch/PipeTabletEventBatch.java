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

import org.apache.iotdb.collector.config.PipeRuntimeOptions;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.collector.plugin.builtin.sink.resource.memory.PipeMemoryBlock;
import org.apache.iotdb.collector.plugin.builtin.sink.resource.memory.PipeMemoryManager;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class PipeTabletEventBatch implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeTabletEventBatch.class);

  protected final List<PipeRawTabletInsertionEvent> events = new ArrayList<>();

  private final int maxDelayInMs;
  private long firstEventProcessingTime = Long.MIN_VALUE;

  private final PipeMemoryBlock allocatedMemoryBlock;

  protected long totalBufferSize = 0;

  protected volatile boolean isClosed = false;

  protected PipeTabletEventBatch(final int maxDelayInMs, final long requestMaxBatchSizeInBytes) {
    this.maxDelayInMs = maxDelayInMs;

    // limit in buffer size
    this.allocatedMemoryBlock =
        PipeMemoryManager.getInstance()
            .tryAllocate(requestMaxBatchSizeInBytes)
            .setShrinkMethod(oldMemory -> Math.max(oldMemory / 2, 0))
            .setShrinkCallback(
                (oldMemory, newMemory) ->
                    LOGGER.info(
                        "The batch size limit has shrunk from {} to {}.", oldMemory, newMemory))
            .setExpandMethod(
                oldMemory -> Math.min(Math.max(oldMemory, 1) * 2, requestMaxBatchSizeInBytes))
            .setExpandCallback(
                (oldMemory, newMemory) ->
                    LOGGER.info(
                        "The batch size limit has expanded from {} to {}.", oldMemory, newMemory));

    if (getMaxBatchSizeInBytes() != requestMaxBatchSizeInBytes) {
      LOGGER.info(
          "PipeTabletEventBatch: the max batch size is adjusted from {} to {} due to the "
              + "memory restriction",
          requestMaxBatchSizeInBytes,
          getMaxBatchSizeInBytes());
    }
  }

  /**
   * Try offer {@link Event} into batch if the given {@link Event} is not duplicated.
   *
   * @param event the given {@link Event}
   * @return {@code true} if the batch can be transferred
   */
  public synchronized boolean onEvent(final TabletInsertionEvent event) throws IOException {
    // TODO consider using a more generic event instead
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
        || System.currentTimeMillis() - firstEventProcessingTime >= maxDelayInMs
        || events.size() > PipeRuntimeOptions.PIPE_MAX_ALLOWED_EVENT_COUNT_IN_TABLET_BATCH.value();
  }

  private long getMaxBatchSizeInBytes() {
    return allocatedMemoryBlock.getMemoryUsageInBytes();
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

    if (allocatedMemoryBlock != null) {
      allocatedMemoryBlock.close();
    }
  }

  public List<PipeRawTabletInsertionEvent> deepCopyEvents() {
    return new ArrayList<>(events);
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }
}
