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
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_FORMAT_HYBRID_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_FORMAT_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_FORMAT_TS_FILE_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_IOTDB_BATCH_DELAY_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_IOTDB_BATCH_SIZE_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_IOTDB_PLAIN_BATCH_DELAY_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_IOTDB_PLAIN_BATCH_SIZE_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_IOTDB_TS_FILE_BATCH_DELAY_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_IOTDB_TS_FILE_BATCH_SIZE_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_LEADER_CACHE_ENABLE_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_LEADER_CACHE_ENABLE_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_FORMAT_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_IOTDB_BATCH_DELAY_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_IOTDB_BATCH_SIZE_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_LEADER_CACHE_ENABLE_KEY;

public class PipeTransferBatchReqBuilder implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeTransferBatchReqBuilder.class);

  private final boolean useLeaderCache;

  private final int requestMaxDelayInMs;
  private final long requestMaxBatchSizeInBytes;

  // If the leader cache is disabled (or unable to find the endpoint of event in the leader cache),
  // the event will be stored in the default batch.
  private final PipeTabletEventBatch defaultBatch;
  // If the leader cache is enabled, the batch will be divided by the leader endpoint,
  // each endpoint has a batch.
  // This is only used in plain batch since tsfile does not return redirection info.
  private final Map<TEndPoint, PipeTabletEventPlainBatch> endPointToBatch = new HashMap<>();

  public PipeTransferBatchReqBuilder(final PipeParameters parameters) {
    final boolean usingTsFileBatch =
        parameters
            .getStringOrDefault(
                Arrays.asList(CONNECTOR_FORMAT_KEY, SINK_FORMAT_KEY), CONNECTOR_FORMAT_HYBRID_VALUE)
            .equals(CONNECTOR_FORMAT_TS_FILE_VALUE);

    useLeaderCache =
        !usingTsFileBatch
            && parameters.getBooleanOrDefault(
                Arrays.asList(SINK_LEADER_CACHE_ENABLE_KEY, CONNECTOR_LEADER_CACHE_ENABLE_KEY),
                CONNECTOR_LEADER_CACHE_ENABLE_DEFAULT_VALUE);

    final int requestMaxDelayInSeconds;
    if (usingTsFileBatch) {
      requestMaxDelayInSeconds =
          parameters.getIntOrDefault(
              Arrays.asList(CONNECTOR_IOTDB_BATCH_DELAY_KEY, SINK_IOTDB_BATCH_DELAY_KEY),
              CONNECTOR_IOTDB_TS_FILE_BATCH_DELAY_DEFAULT_VALUE);
      requestMaxDelayInMs =
          requestMaxDelayInSeconds < 0 ? Integer.MAX_VALUE : requestMaxDelayInSeconds * 1000;
      requestMaxBatchSizeInBytes =
          parameters.getLongOrDefault(
              Arrays.asList(CONNECTOR_IOTDB_BATCH_SIZE_KEY, SINK_IOTDB_BATCH_SIZE_KEY),
              CONNECTOR_IOTDB_TS_FILE_BATCH_SIZE_DEFAULT_VALUE);
      this.defaultBatch =
          new PipeTabletEventTsFileBatch(requestMaxDelayInMs, requestMaxBatchSizeInBytes);
    } else {
      requestMaxDelayInSeconds =
          parameters.getIntOrDefault(
              Arrays.asList(CONNECTOR_IOTDB_BATCH_DELAY_KEY, SINK_IOTDB_BATCH_DELAY_KEY),
              CONNECTOR_IOTDB_PLAIN_BATCH_DELAY_DEFAULT_VALUE);
      requestMaxDelayInMs =
          requestMaxDelayInSeconds < 0 ? Integer.MAX_VALUE : requestMaxDelayInSeconds * 1000;
      requestMaxBatchSizeInBytes =
          parameters.getLongOrDefault(
              Arrays.asList(CONNECTOR_IOTDB_BATCH_SIZE_KEY, SINK_IOTDB_BATCH_SIZE_KEY),
              CONNECTOR_IOTDB_PLAIN_BATCH_SIZE_DEFAULT_VALUE);
      this.defaultBatch =
          new PipeTabletEventPlainBatch(requestMaxDelayInMs, requestMaxBatchSizeInBytes);
    }
  }

  /**
   * Try offer {@link Event} into the corresponding batch if the given {@link Event} is not
   * duplicated.
   *
   * @param event the given {@link Event}
   * @return {@link Pair}<{@link TEndPoint}, {@link PipeTabletEventPlainBatch}> not null means this
   *     {@link PipeTabletEventPlainBatch} can be transferred. the first element is the leader
   *     endpoint to transfer to (might be null), the second element is the batch to be transferred.
   */
  public synchronized Pair<TEndPoint, PipeTabletEventBatch> onEvent(
      final TabletInsertionEvent event) throws IOException {
    if (!(event instanceof PipeRawTabletInsertionEvent)) {
      LOGGER.warn(
          "Unsupported event {} type {} when building transfer request", event, event.getClass());
      return null;
    }

    if (!useLeaderCache) {
      return defaultBatch.onEvent(event) ? new Pair<>(null, defaultBatch) : null;
    }

    String deviceId = ((PipeRawTabletInsertionEvent) event).getDeviceId();

    if (Objects.isNull(deviceId)) {
      return defaultBatch.onEvent(event) ? new Pair<>(null, defaultBatch) : null;
    }

    final TEndPoint endPoint =
        new TEndPoint(PipeRuntimeOptions.RPC_ADDRESS.value(), PipeRuntimeOptions.RPC_PORT.value());

    final PipeTabletEventPlainBatch batch =
        endPointToBatch.computeIfAbsent(
            endPoint,
            k -> new PipeTabletEventPlainBatch(requestMaxDelayInMs, requestMaxBatchSizeInBytes));
    return batch.onEvent(event) ? new Pair<>(endPoint, batch) : null;
  }

  /** Get all batches that have at least 1 event. */
  public synchronized List<Pair<TEndPoint, PipeTabletEventBatch>> getAllNonEmptyBatches() {
    final List<Pair<TEndPoint, PipeTabletEventBatch>> nonEmptyBatches = new ArrayList<>();
    if (!defaultBatch.isEmpty()) {
      nonEmptyBatches.add(new Pair<>(null, defaultBatch));
    }
    endPointToBatch.forEach(
        (endPoint, batch) -> {
          if (!batch.isEmpty()) {
            nonEmptyBatches.add(new Pair<>(endPoint, batch));
          }
        });
    return nonEmptyBatches;
  }

  public boolean isEmpty() {
    return defaultBatch.isEmpty()
        && endPointToBatch.values().stream().allMatch(PipeTabletEventPlainBatch::isEmpty);
  }

  @Override
  public synchronized void close() {
    defaultBatch.close();
    endPointToBatch.values().forEach(PipeTabletEventPlainBatch::close);
  }
}
