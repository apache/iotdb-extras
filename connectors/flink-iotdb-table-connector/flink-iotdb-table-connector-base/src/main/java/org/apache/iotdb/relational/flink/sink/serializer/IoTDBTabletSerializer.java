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
 * Serializes Flink input records into a writer-owned IoTDB {@link Tablet}.
 *
 * <p>The sink writer owns tablet creation, batching, and flushing. The serializer only defines how
 * an input record is appended to the provided tablet.
 *
 * @param <IN> input record type
 */
public interface IoTDBTabletSerializer<IN> extends Serializable {

  /** Opens the serializer before the first record is serialized. */
  default void open() throws Exception {}

  /**
   * Creates the writer-owned tablet used for batching.
   *
   * <p>The returned tablet must have a positive maximum row number. The writer owns the returned
   * instance and reuses it until it is flushed.
   */
  Tablet createTablet(int maxRows) throws IOException;

  /**
   * Serializes one input record directly into the writer-owned tablet.
   *
   * @return {@code true} if the tablet is full after serialization, otherwise {@code false}
   */
  boolean serialize(IN record, Tablet tablet) throws IOException;

  /** Closes the serializer after the last record has been processed. */
  default void close() throws Exception {}
}
