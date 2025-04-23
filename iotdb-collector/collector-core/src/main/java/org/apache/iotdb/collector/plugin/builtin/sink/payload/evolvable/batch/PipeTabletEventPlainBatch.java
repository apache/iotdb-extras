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
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferTabletBatchReqV2;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import org.apache.tsfile.utils.Pair;
import org.apache.tsfile.utils.PublicBAOS;
import org.apache.tsfile.utils.ReadWriteIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PipeTabletEventPlainBatch extends PipeTabletEventBatch {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeTabletEventPlainBatch.class);

  private final List<ByteBuffer> binaryBuffers = new ArrayList<>();
  private final List<ByteBuffer> insertNodeBuffers = new ArrayList<>();
  private final List<ByteBuffer> tabletBuffers = new ArrayList<>();

  private static final String TREE_MODEL_DATABASE_PLACEHOLDER = null;
  private final List<String> binaryDataBases = new ArrayList<>();
  private final List<String> insertNodeDataBases = new ArrayList<>();
  private final List<String> tabletDataBases = new ArrayList<>();

  // Used to rate limit when transferring data
  private final Map<Pair<String, Long>, Long> pipe2BytesAccumulated = new HashMap<>();

  PipeTabletEventPlainBatch(final int maxDelayInMs, final long requestMaxBatchSizeInBytes) {
    super(maxDelayInMs, requestMaxBatchSizeInBytes);
  }

  @Override
  protected boolean constructBatch(final TabletInsertionEvent event) throws IOException {
    final int bufferSize = buildTabletInsertionBuffer(event);
    totalBufferSize += bufferSize;
    pipe2BytesAccumulated.compute(
        new Pair<>("", 0L),
        (pipeName, bytesAccumulated) ->
            bytesAccumulated == null ? bufferSize : bytesAccumulated + bufferSize);
    return true;
  }

  @Override
  public synchronized void onSuccess() {
    super.onSuccess();

    binaryBuffers.clear();
    insertNodeBuffers.clear();
    tabletBuffers.clear();

    binaryDataBases.clear();
    insertNodeDataBases.clear();
    tabletDataBases.clear();

    pipe2BytesAccumulated.clear();
  }

  public PipeTransferTabletBatchReqV2 toTPipeTransferReq() throws IOException {
    return PipeTransferTabletBatchReqV2.toTPipeTransferReq(
        binaryBuffers,
        insertNodeBuffers,
        tabletBuffers,
        binaryDataBases,
        insertNodeDataBases,
        tabletDataBases);
  }

  private int buildTabletInsertionBuffer(final TabletInsertionEvent event) throws IOException {
    int databaseEstimateSize;
    final ByteBuffer buffer;

    final PipeRawTabletInsertionEvent pipeRawTabletInsertionEvent =
        (PipeRawTabletInsertionEvent) event;
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      pipeRawTabletInsertionEvent.convertToTablet().serialize(outputStream);
      ReadWriteIOUtils.write(pipeRawTabletInsertionEvent.isAligned(), outputStream);
      buffer = ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size());
    }
    tabletBuffers.add(buffer);
    if (pipeRawTabletInsertionEvent.isTableModelEvent()) {
      databaseEstimateSize = pipeRawTabletInsertionEvent.getTableModelDatabaseName().length();
      tabletDataBases.add(pipeRawTabletInsertionEvent.getTableModelDatabaseName());
    } else {
      databaseEstimateSize = 4;
      tabletDataBases.add(TREE_MODEL_DATABASE_PLACEHOLDER);
    }

    return buffer.limit() + databaseEstimateSize;
  }
}
