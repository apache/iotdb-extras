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

package org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request;

import org.apache.iotdb.service.rpc.thrift.TPipeTransferReq;

import org.apache.tsfile.utils.PublicBAOS;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PipeTransferTabletBatchReqV2 extends TPipeTransferReq {

  private final transient List<PipeTransferTabletBinaryReqV2> binaryReqs = new ArrayList<>();
  private final transient List<PipeTransferTabletRawReqV2> tabletReqs = new ArrayList<>();

  private PipeTransferTabletBatchReqV2() {
    // Empty constructor
  }

  /////////////////////////////// Thrift ///////////////////////////////

  public static PipeTransferTabletBatchReqV2 toTPipeTransferReq(
      final List<ByteBuffer> binaryBuffers,
      final List<ByteBuffer> insertNodeBuffers,
      final List<ByteBuffer> tabletBuffers,
      final List<String> binaryDataBases,
      final List<String> insertNodeDataBases,
      final List<String> tabletDataBases)
      throws IOException {
    final PipeTransferTabletBatchReqV2 batchReq = new PipeTransferTabletBatchReqV2();

    batchReq.version = IoTDBConnectorRequestVersion.VERSION_1.getVersion();
    batchReq.type = PipeRequestType.TRANSFER_TABLET_BATCH_V2.getType();
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      ReadWriteIOUtils.write(binaryBuffers.size(), outputStream);
      for (int i = 0; i < binaryBuffers.size(); i++) {
        final ByteBuffer binaryBuffer = binaryBuffers.get(i);
        ReadWriteIOUtils.write(binaryBuffer.limit(), outputStream);
        outputStream.write(binaryBuffer.array(), 0, binaryBuffer.limit());
        ReadWriteIOUtils.write(binaryDataBases.get(i), outputStream);
      }

      ReadWriteIOUtils.write(insertNodeBuffers.size(), outputStream);
      for (int i = 0; i < insertNodeBuffers.size(); i++) {
        final ByteBuffer insertNodeBuffer = insertNodeBuffers.get(i);
        outputStream.write(insertNodeBuffer.array(), 0, insertNodeBuffer.limit());
        ReadWriteIOUtils.write(insertNodeDataBases.get(i), outputStream);
      }

      ReadWriteIOUtils.write(tabletBuffers.size(), outputStream);
      for (int i = 0; i < tabletBuffers.size(); i++) {
        final ByteBuffer tabletBuffer = tabletBuffers.get(i);
        outputStream.write(tabletBuffer.array(), 0, tabletBuffer.limit());
        ReadWriteIOUtils.write(tabletDataBases.get(i), outputStream);
      }

      batchReq.body =
          ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size());
    }

    return batchReq;
  }

  // public static PipeTransferTabletBatchReqV2 fromTPipeTransferReq(
  //     final TPipeTransferReq transferReq) {
  //   final PipeTransferTabletBatchReqV2 batchReq = new PipeTransferTabletBatchReqV2();
  //
  //   int size = ReadWriteIOUtils.readInt(transferReq.body);
  //   for (int i = 0; i < size; ++i) {
  //     final int length = ReadWriteIOUtils.readInt(transferReq.body);
  //     final byte[] body = new byte[length];
  //     transferReq.body.get(body);
  //     batchReq.binaryReqs.add(
  //         PipeTransferTabletBinaryReqV2.toTPipeTransferBinaryReq(
  //             ByteBuffer.wrap(body), ReadWriteIOUtils.readString(transferReq.body)));
  //   }
  //
  //   size = ReadWriteIOUtils.readInt(transferReq.body);
  //   for (int i = 0; i < size; ++i) {
  //     batchReq.insertNodeReqs.add(
  //         PipeTransferTabletInsertNodeReqV2.toTabletInsertNodeReq(
  //             (InsertNode) PlanFragment.deserializeHelper(transferReq.body, null),
  //             ReadWriteIOUtils.readString(transferReq.body)));
  //   }
  //
  //   size = ReadWriteIOUtils.readInt(transferReq.body);
  //   for (int i = 0; i < size; ++i) {
  //     batchReq.tabletReqs.add(
  //         PipeTransferTabletRawReqV2.toTPipeTransferRawReq(
  //             Tablet.deserialize(transferReq.body),
  //             ReadWriteIOUtils.readBool(transferReq.body),
  //             ReadWriteIOUtils.readString(transferReq.body)));
  //   }
  //
  //   batchReq.version = transferReq.version;
  //   batchReq.type = transferReq.type;
  //   batchReq.body = transferReq.body;
  //
  //   return batchReq;
  // }

  /////////////////////////////// Object ///////////////////////////////

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    final PipeTransferTabletBatchReqV2 that = (PipeTransferTabletBatchReqV2) obj;
    return Objects.equals(binaryReqs, that.binaryReqs)
        && Objects.equals(tabletReqs, that.tabletReqs)
        && version == that.version
        && type == that.type
        && Objects.equals(body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(binaryReqs, tabletReqs, version, type, body);
  }
}
