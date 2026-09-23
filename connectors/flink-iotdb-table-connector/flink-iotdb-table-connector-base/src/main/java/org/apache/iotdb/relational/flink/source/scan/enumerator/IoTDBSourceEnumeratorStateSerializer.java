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

package org.apache.iotdb.relational.flink.source.scan.enumerator;

import org.apache.iotdb.relational.flink.source.scan.split.IoTDBSourceSplit;
import org.apache.iotdb.relational.flink.source.scan.split.IoTDBSourceSplitSerializer;

import org.apache.flink.core.io.SimpleVersionedSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Serializer for {@link IoTDBSourceEnumeratorState}. */
public class IoTDBSourceEnumeratorStateSerializer
    implements SimpleVersionedSerializer<IoTDBSourceEnumeratorState> {

  private static final int VERSION = 1;

  private final IoTDBSourceSplitSerializer splitSerializer = new IoTDBSourceSplitSerializer();

  @Override
  public int getVersion() {
    return VERSION;
  }

  @Override
  public byte[] serialize(IoTDBSourceEnumeratorState state) throws IOException {
    List<IoTDBSourceSplit> splits = state.getRemainingSplits();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      output.writeInt(splits.size());
      for (IoTDBSourceSplit split : splits) {
        byte[] serializedSplit = splitSerializer.serialize(split);
        output.writeInt(serializedSplit.length);
        output.write(serializedSplit);
      }
    }
    return bytes.toByteArray();
  }

  @Override
  public IoTDBSourceEnumeratorState deserialize(int version, byte[] serialized) throws IOException {
    if (version != VERSION) {
      throw new IOException(
          "Unsupported IoTDB source enumerator state serializer version: " + version);
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(serialized))) {
      int splitCount = input.readInt();
      List<IoTDBSourceSplit> splits = new ArrayList<>(splitCount);
      for (int i = 0; i < splitCount; i++) {
        int splitLength = input.readInt();
        byte[] serializedSplit = new byte[splitLength];
        input.readFully(serializedSplit);
        splits.add(splitSerializer.deserialize(splitSerializer.getVersion(), serializedSplit));
      }
      return new IoTDBSourceEnumeratorState(splits);
    }
  }
}
