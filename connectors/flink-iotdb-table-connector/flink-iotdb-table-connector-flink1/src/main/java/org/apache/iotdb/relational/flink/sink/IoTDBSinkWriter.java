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

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.relational.flink.cfg.IoTDBRelationalOptions;
import org.apache.iotdb.relational.flink.sink.serializer.IoTDBTabletSerializer;
import org.apache.iotdb.session.TableSessionBuilder;

import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.tsfile.write.record.Tablet;

import java.io.IOException;

/**
 * Sink writer of the IoTDB relational (table model) Flink connector.
 *
 * <p>The writer owns one IoTDB session and one buffered tablet. Serialized tablets are validated,
 * merged into the buffer, and flushed through {@code ITableSession.insert(Tablet)}.
 *
 * @param <IN> input record type
 */
public class IoTDBSinkWriter<IN> implements SinkWriter<IN> {

  private static final int DEFAULT_BATCH_SIZE = 1024;

  private final IoTDBRelationalOptions options;
  private final IoTDBTabletSerializer<IN> serializer;

  private ITableSession session;
  private Tablet buffer;
  private boolean closed;

  public IoTDBSinkWriter(IoTDBRelationalOptions options, IoTDBTabletSerializer<IN> serializer)
      throws IOException {
    this.options = options;
    this.serializer = serializer;
    open();
  }

  @Override
  public void write(IN element, Context context) throws IOException, InterruptedException {
    ensureOpen();

    if (buffer.getRowSize() >= buffer.getMaxRowNumber()) {
      flushBuffer();
    }

    int rowSizeBefore = buffer.getRowSize();
    boolean full = serializer.serialize(element, buffer);
    int rowSizeAfter = buffer.getRowSize();

    if (rowSizeAfter < rowSizeBefore || rowSizeAfter > buffer.getMaxRowNumber()) {
      throw new IOException("Serializer produced an invalid tablet row count.");
    }
    if (full || rowSizeAfter >= buffer.getMaxRowNumber()) {
      flushBuffer();
    }
  }

  @Override
  public void flush(boolean endOfInput) throws IOException, InterruptedException {
    ensureOpen();
    flushBuffer();
  }

  @Override
  public void close() throws Exception {
    if (closed) {
      return;
    }
    closed = true;

    Exception failure = null;
    try {
      if (session != null) {
        flushBuffer();
      }
    } catch (Exception e) {
      failure = e;
    }

    try {
      serializer.close();
    } catch (Exception e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }

    try {
      if (session != null) {
        session.close();
        session = null;
      }
    } catch (Exception e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  private void open() throws IOException {
    try {
      TableSessionBuilder builder =
          new TableSessionBuilder()
              .nodeUrls(options.getNodeUrls())
              .username(options.getUsername())
              .password(options.getPassword());
      if (options.getDatabase() != null) {
        builder.database(options.getDatabase());
      }
      session = builder.build();
      serializer.open();
      buffer = serializer.createTablet(DEFAULT_BATCH_SIZE);
      if (buffer == null || buffer.getMaxRowNumber() <= 0) {
        throw new IOException("Serializer created an invalid tablet buffer.");
      }
    } catch (Exception e) {
      closeQuietly();
      throw new IOException("Failed to open IoTDB sink writer.", e);
    }
  }

  private void ensureOpen() throws IOException {
    if (closed || session == null) {
      throw new IOException("IoTDB sink writer is already closed.");
    }
  }

  private void flushBuffer() throws IOException {
    if (buffer != null && buffer.getRowSize() > 0) {
      insert(buffer);
      buffer.reset();
    }
  }

  private void insert(Tablet tablet) throws IOException {
    try {
      session.insert(tablet);
    } catch (Exception e) {
      throw new IOException("Failed to insert tablet into IoTDB.", e);
    }
  }

  private void closeQuietly() {
    if (session != null) {
      try {
        session.close();
      } catch (Exception ignored) {
        // Preserve the original open failure.
      } finally {
        session = null;
      }
    }
  }
}
