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

package org.apache.iotdb.relational.flink.source.split;

import org.apache.flink.core.io.SimpleVersionedSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Serializer for {@link IoTDBSourceSplit}. */
public class IoTDBSourceSplitSerializer implements SimpleVersionedSerializer<IoTDBSourceSplit> {

  private static final int VERSION = 1;

  @Override
  public int getVersion() {
    return VERSION;
  }

  @Override
  public byte[] serialize(IoTDBSourceSplit split) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      writeString(output, split.splitId());
      writeString(output, split.getDatabase());
      writeString(output, split.getTable());
      writeString(output, split.getSql());
    }
    return bytes.toByteArray();
  }

  @Override
  public IoTDBSourceSplit deserialize(int version, byte[] serialized) throws IOException {
    if (version != VERSION) {
      throw new IOException("Unsupported IoTDB source split serializer version: " + version);
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(serialized))) {
      return new IoTDBSourceSplit(
          readString(input), readString(input), readString(input), readString(input));
    }
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0) {
      return null;
    }
    byte[] bytes = new byte[length];
    input.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
