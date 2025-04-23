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
import org.apache.tsfile.write.record.Tablet;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

public class PipeTransferTabletRawReqV2 extends PipeTransferTabletRawReq {

  protected transient String dataBaseName;

  /////////////////////////////// WriteBack & Batch ///////////////////////////////

  public static PipeTransferTabletRawReqV2 toTPipeTransferRawReq(
      final Tablet tablet, final boolean isAligned, final String dataBaseName) {
    final PipeTransferTabletRawReqV2 tabletReq = new PipeTransferTabletRawReqV2();

    tabletReq.tablet = tablet;
    tabletReq.isAligned = isAligned;
    tabletReq.dataBaseName = dataBaseName;
    tabletReq.version = IoTDBConnectorRequestVersion.VERSION_1.getVersion();
    tabletReq.type = PipeRequestType.TRANSFER_TABLET_RAW_V2.getType();

    return tabletReq;
  }

  /////////////////////////////// Thrift ///////////////////////////////

  public static PipeTransferTabletRawReqV2 toTPipeTransferReq(
      final Tablet tablet, final boolean isAligned, final String dataBaseName) throws IOException {
    PipeTransferTabletRawReqV2 tabletReq = toTPipeTransferRawReq(tablet, isAligned, dataBaseName);

    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      tablet.serialize(outputStream);
      ReadWriteIOUtils.write(isAligned, outputStream);
      ReadWriteIOUtils.write(dataBaseName, outputStream);
      tabletReq.body =
          ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size());
    }

    return tabletReq;
  }

  public static PipeTransferTabletRawReqV2 fromTPipeTransferReq(
      final TPipeTransferReq transferReq) {
    final PipeTransferTabletRawReqV2 tabletReq = new PipeTransferTabletRawReqV2();

    tabletReq.tablet = Tablet.deserialize(transferReq.body);
    tabletReq.isAligned = ReadWriteIOUtils.readBool(transferReq.body);
    tabletReq.dataBaseName = ReadWriteIOUtils.readString(transferReq.body);

    tabletReq.version = transferReq.version;
    tabletReq.type = transferReq.type;
    tabletReq.body = transferReq.body;

    return tabletReq;
  }

  /////////////////////////////// Air Gap ///////////////////////////////

  public static byte[] toTPipeTransferBytes(
      final Tablet tablet, final boolean isAligned, final String dataBaseName) throws IOException {
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      ReadWriteIOUtils.write(IoTDBConnectorRequestVersion.VERSION_1.getVersion(), outputStream);
      ReadWriteIOUtils.write(PipeRequestType.TRANSFER_TABLET_RAW_V2.getType(), outputStream);
      tablet.serialize(outputStream);
      ReadWriteIOUtils.write(isAligned, outputStream);
      ReadWriteIOUtils.write(dataBaseName, outputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }

  /////////////////////////////// Object ///////////////////////////////

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final PipeTransferTabletRawReqV2 that = (PipeTransferTabletRawReqV2) o;
    return Objects.equals(dataBaseName, that.dataBaseName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), dataBaseName);
  }
}
