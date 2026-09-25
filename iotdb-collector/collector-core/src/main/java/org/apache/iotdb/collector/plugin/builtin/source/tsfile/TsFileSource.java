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

package org.apache.iotdb.collector.plugin.builtin.source.tsfile;

import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeTsFileInsertionEvent;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;

import static org.apache.iotdb.collector.plugin.builtin.source.tsfile.TsFileSourceConstant.TS_FILE_SOURCE_DIR;

public class TsFileSource extends PullSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(TsFileSource.class);

  private String tsFileDirectory;

  private static final Integer EVENT_QUEUE_CAPACITY = 1000;
  private final BlockingQueue<Event> eventQueue = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
  private final Set<String> fileSet = new HashSet<>();

  private volatile boolean isStarted;
  private Thread workerThread;

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);

    CollectorParameters.validateStringRequiredParam(validator, TS_FILE_SOURCE_DIR);
  }

  public void customize(
      PipeParameters pipeParameters, PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    super.customize(pipeParameters, pipeSourceRuntimeConfiguration);

    tsFileDirectory = pipeParameters.getString(TS_FILE_SOURCE_DIR);
  }

  @Override
  public Optional<ProgressIndex> report() {
    return Optional.empty();
  }

  @Override
  public void start() throws Exception {
    if (workerThread == null) {
      isStarted = true;

      workerThread = new Thread(this::doWork);
      workerThread.setName("TsFileSourceWorker-" + instanceIndex);
      workerThread.start();
    }
  }

  private void doWork() {
    while (isStarted && !Thread.currentThread().isInterrupted()) {
      try {
        clearTransferFiles();

        final File tsFileDir = new File(tsFileDirectory);
        for (final File file : Objects.requireNonNull(tsFileDir.listFiles())) {
          if (fileSet.contains(file.getName())) {
            continue;
          }

          eventQueue.put(new PipeTsFileInsertionEvent(file));
        }

        LockSupport.parkNanos(1_000_000_000);
      } catch (final InterruptedException e) {
        LOGGER.warn("Failed to collect file");
      }
    }
  }

  private void clearTransferFiles() {
    final Set<String> currentFileSet = new HashSet<>();
    final File tsFileDir = new File(tsFileDirectory);

    for (final File file : Objects.requireNonNull(tsFileDir.listFiles())) {
      currentFileSet.add(file.getName());
    }

    fileSet.retainAll(currentFileSet);
  }

  @Override
  public Event supply() throws Exception {
    return eventQueue.take();
  }

  @Override
  public void close() throws Exception {
    isStarted = false;

    if (workerThread != null) {
      workerThread.interrupt();
      try {
        workerThread.join(1000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    workerThread = null;
    fileSet.clear();
  }
}
