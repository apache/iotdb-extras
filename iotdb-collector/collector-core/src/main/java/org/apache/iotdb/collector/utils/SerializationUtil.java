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

package org.apache.iotdb.collector.utils;

import org.apache.iotdb.collector.runtime.progress.ProgressIndex;

import org.apache.tsfile.utils.PublicBAOS;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class SerializationUtil {

  public static byte[] serialize(Map<String, String> map) throws IOException {
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      ReadWriteIOUtils.write(map.size(), outputStream);
      for (final Map.Entry<String, String> entry : map.entrySet()) {
        ReadWriteIOUtils.write(entry.getKey(), outputStream);
        ReadWriteIOUtils.write(entry.getValue(), outputStream);
      }
      return ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size())
          .array();
    }
  }

  public static Map<String, String> deserialize(final byte[] buffer) {
    final Map<String, String> attribute = new HashMap<>();
    final ByteBuffer attributeBuffer = ByteBuffer.wrap(buffer);

    final int size = ReadWriteIOUtils.readInt(attributeBuffer);
    for (int i = 0; i < size; i++) {
      final String key = ReadWriteIOUtils.readString(attributeBuffer);
      final String value = ReadWriteIOUtils.readString(attributeBuffer);

      attribute.put(key, value);
    }

    return attribute;
  }

  public static byte[] serializeInstances(final Map<Integer, ProgressIndex> map)
      throws IOException {
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      ReadWriteIOUtils.write(map.size(), outputStream);
      for (final Map.Entry<Integer, ProgressIndex> entry : map.entrySet()) {
        ReadWriteIOUtils.write(entry.getKey(), outputStream);
        ReadWriteIOUtils.write(entry.getValue().getProgressInfo(), outputStream);
      }
      return ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size())
          .array();
    }
  }

  public static Map<Integer, ProgressIndex> deserializeInstances(final byte[] buffer) {
    final Map<Integer, ProgressIndex> attribute = new HashMap<>();
    final ByteBuffer attributeBuffer = ByteBuffer.wrap(buffer);

    final int size = ReadWriteIOUtils.readInt(attributeBuffer);
    for (int i = 0; i < size; i++) {
      final Integer instanceIndex = ReadWriteIOUtils.readInt(attributeBuffer);
      final Map<String, String> attributeMap = ReadWriteIOUtils.readMap(attributeBuffer);

      attribute.put(instanceIndex, new ProgressIndex(instanceIndex, attributeMap));
    }

    return attribute;
  }
}
