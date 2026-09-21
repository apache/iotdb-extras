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

package org.apache.iotdb.relational.flink.source.lookup;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.AsyncLookupFunction;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.types.DataType;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous lookup function that queries an IoTDB table-model table by the join keys.
 *
 * <p>{@code ITableSession} only offers blocking queries, so the blocking {@link IoTDBLookupReader}
 * is executed on a bounded thread pool and results are delivered through {@link CompletableFuture}.
 */
public class IoTDBAsyncLookupFunction extends AsyncLookupFunction {

  private static final long serialVersionUID = 1L;

  private final IoTDBOptions options;
  private final DataType rowDataType;
  private final int[] keyIndices;
  private final int threadSize;

  private transient ExecutorService executor;
  private transient IoTDBLookupReader reader;

  public IoTDBAsyncLookupFunction(
      IoTDBOptions options, DataType rowDataType, int[] keyIndices, int threadSize) {
    this.options = options;
    this.rowDataType = rowDataType;
    this.keyIndices = keyIndices.clone();
    this.threadSize = threadSize;
  }

  @Override
  public void open(FunctionContext context) {
    reader = new IoTDBLookupReader(options, rowDataType, keyIndices);
    reader.open();
    executor = Executors.newFixedThreadPool(Math.max(1, threadSize), createThreadFactory());
  }

  @Override
  public CompletableFuture<Collection<RowData>> asyncLookup(RowData keyRow) {
    if (keyRow == null) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    return CompletableFuture.<Collection<RowData>>supplyAsync(
        () -> {
          try {
            return reader.get(keyRow);
          } catch (IOException e) {
            throw new CompletionException(e);
          }
        },
        executor);
  }

  @Override
  public void close() {
    if (reader != null) {
      reader.close();
    }
    if (executor != null) {
      executor.shutdown();
    }
  }

  private static ThreadFactory createThreadFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "iotdb-lookup-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
