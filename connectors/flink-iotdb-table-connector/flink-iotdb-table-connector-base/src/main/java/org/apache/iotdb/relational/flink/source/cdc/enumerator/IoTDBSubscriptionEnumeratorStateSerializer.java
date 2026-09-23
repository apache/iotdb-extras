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

package org.apache.iotdb.relational.flink.source.cdc.enumerator;

import org.apache.iotdb.relational.flink.source.cdc.split.IoTDBSubscriptionSplit;
import org.apache.iotdb.relational.flink.source.cdc.split.IoTDBSubscriptionSplitSerializer;

import org.apache.flink.core.io.SimpleVersionedSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Serializer for {@link IoTDBSubscriptionEnumeratorState}. */
public class IoTDBSubscriptionEnumeratorStateSerializer
    implements SimpleVersionedSerializer<IoTDBSubscriptionEnumeratorState> {

  private static final int VERSION = 1;

  private final IoTDBSubscriptionSplitSerializer splitSerializer =
      new IoTDBSubscriptionSplitSerializer();

  @Override
  public int getVersion() {
    return VERSION;
  }

  @Override
  public byte[] serialize(IoTDBSubscriptionEnumeratorState state) throws IOException {
    try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer)) {
      out.writeBoolean(state.isAllSplitsCreated());
      List<IoTDBSubscriptionSplit> splits = state.getRemainingSplits();
      out.writeInt(splits.size());
      for (IoTDBSubscriptionSplit split : splits) {
        byte[] bytes = splitSerializer.serialize(split);
        out.writeInt(bytes.length);
        out.write(bytes);
      }
      out.flush();
      return buffer.toByteArray();
    }
  }

  @Override
  public IoTDBSubscriptionEnumeratorState deserialize(int version, byte[] serialized)
      throws IOException {
    try (ByteArrayInputStream buffer = new ByteArrayInputStream(serialized);
        DataInputStream in = new DataInputStream(buffer)) {
      boolean allSplitsCreated = in.readBoolean();
      int size = in.readInt();
      List<IoTDBSubscriptionSplit> splits = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        splits.add(splitSerializer.deserialize(splitSerializer.getVersion(), bytes));
      }
      return new IoTDBSubscriptionEnumeratorState(allSplitsCreated, splits);
    }
  }
}
