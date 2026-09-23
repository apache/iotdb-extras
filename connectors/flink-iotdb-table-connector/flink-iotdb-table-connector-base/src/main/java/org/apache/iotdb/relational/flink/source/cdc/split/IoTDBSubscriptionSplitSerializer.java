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

package org.apache.iotdb.relational.flink.source.cdc.split;

import org.apache.flink.core.io.SimpleVersionedSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Serializer for {@link IoTDBSubscriptionSplit}. */
public class IoTDBSubscriptionSplitSerializer
    implements SimpleVersionedSerializer<IoTDBSubscriptionSplit> {

  private static final int VERSION = 1;

  @Override
  public int getVersion() {
    return VERSION;
  }

  @Override
  public byte[] serialize(IoTDBSubscriptionSplit split) throws IOException {
    try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer)) {
      out.writeUTF(split.getTopic());
      out.writeUTF(split.getConsumerGroup());
      out.flush();
      return buffer.toByteArray();
    }
  }

  @Override
  public IoTDBSubscriptionSplit deserialize(int version, byte[] serialized) throws IOException {
    try (ByteArrayInputStream buffer = new ByteArrayInputStream(serialized);
        DataInputStream in = new DataInputStream(buffer)) {
      String topic = in.readUTF();
      String consumerGroup = in.readUTF();
      return new IoTDBSubscriptionSplit(topic, consumerGroup);
    }
  }
}
