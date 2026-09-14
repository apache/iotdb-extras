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

package org.apache.iotdb.relational.flink.sink.serializer;

import org.apache.tsfile.write.record.Tablet;

import java.io.IOException;
import java.io.Serializable;

/**
 * Converts one Flink input record into an IoTDB {@link Tablet}.
 *
 * <p>The returned tablet may contain zero, one, or multiple rows. The sink writer owns batching and
 * flushing, while the serializer only defines how an input record is represented as tablet rows.
 *
 * @param <IN> input record type
 */
public interface IoTDBTabletSerializer<IN> extends Serializable {

  /** Opens the serializer before the first record is serialized. */
  default void open() throws Exception {}

  /**
   * Serializes one input record.
   *
   * <p>TODO: define the exact owner of the returned tablet and the batch-size contract.
   */
  Tablet serialize(IN record) throws IOException;

  /** Closes the serializer after the last record has been processed. */
  default void close() throws Exception {}
}
