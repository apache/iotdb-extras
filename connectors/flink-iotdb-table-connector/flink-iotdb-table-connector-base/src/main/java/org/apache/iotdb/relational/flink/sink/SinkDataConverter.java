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

package org.apache.iotdb.relational.flink.sink;

import org.apache.tsfile.utils.Binary;

import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * Converts one sink input element into zero or more IoTDB rows.
 *
 * <p>Implementations only need to understand the input type {@code IN} and how to expose the
 * columns of the IoTDB table; they do not need to know how the rows are buffered or written.
 *
 * <p>Usage: call {@link #getIterator(Object)} for each input element and consume the returned
 * {@link Iterator}; column indexes follow the resolved IoTDB table column order (including the TIME
 * column).
 *
 * @param <IN> input element type
 */
public interface SinkDataConverter<IN> extends Serializable {

  /** Opens the converter before the first input is processed. */
  default void open() throws Exception {}

  /** Creates an iterator that reads the rows produced by the given input element. */
  Iterator getIterator(IN record) throws IOException;

  /** Closes the converter after the last input has been processed. */
  default void close() throws Exception {}

  /** Cursor over the IoTDB rows produced by one input element. */
  interface Iterator extends AutoCloseable {

    /** Advances to the next row; returns {@code false} when there are no more rows. */
    boolean next() throws IOException;

    boolean isNull(int columnIndex);

    boolean getBoolean(int columnIndex) throws IOException;

    int getInt(int columnIndex) throws IOException;

    long getLong(int columnIndex) throws IOException;

    float getFloat(int columnIndex) throws IOException;

    double getDouble(int columnIndex) throws IOException;

    String getString(int columnIndex) throws IOException;

    Binary getBlob(int columnIndex) throws IOException;

    LocalDate getDate(int columnIndex) throws IOException;

    /** Returns the TIME value of the current row as epoch milliseconds. */
    long getTimestamp(int columnIndex) throws IOException;

    @Override
    default void close() throws IOException {}
  }
}
