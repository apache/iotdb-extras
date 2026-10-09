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

package org.apache.iotdb.collector.runtime.task;

import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);

  private static final long WORKER_SHUTDOWN_TIMEOUT_MS = 10_000L;

  protected final String taskId;
  protected final PipeParameters parameters;

  protected final int parallelism;

  protected final TaskDispatch dispatch;

  protected Task(
      final String taskId,
      final Map<String, String> attributes,
      final String parallelismKey,
      final int parallelismValue) {
    this.taskId = taskId;
    this.parameters = new PipeParameters(attributes);

    this.parallelism = parameters.getIntOrDefault(parallelismKey, parallelismValue);

    this.dispatch = new TaskDispatch();
  }

  public final synchronized void create() throws Exception {
    dispatch.resume();
    createInternal();
  }

  public abstract void createInternal() throws Exception;

  public final synchronized void start() throws Exception {
    dispatch.resume();
    startInternal();
  }

  public abstract void startInternal() throws Exception;

  public final synchronized void stop() throws Exception {
    dispatch.pause();
    stopInternal();
  }

  public abstract void stopInternal() throws Exception;

  public final synchronized void drop() throws Exception {
    dispatch.remove();
    dropInternal();
  }

  public abstract void dropInternal() throws Exception;

  /** Lets the workers of this stage run to completion; {@link #drop()} does the same first. */
  final synchronized void markDropped() {
    dispatch.remove();
  }

  /**
   * Stops the workers of a started or never-started disruptor and the executor they run on. The
   * backlog is drained for a bounded time; afterwards the workers are halted until the executor has
   * terminated. Disruptor 3.x discards a halt that reaches a worker before it enters {@code run()},
   * and that worker then parks forever, so one halt right after a quick creation failure is not
   * enough.
   */
  protected static void stopWorkers(final Disruptor<?> disruptor, final ExecutorService executor) {
    try {
      disruptor.shutdown(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (final TimeoutException e) {
      LOGGER.warn("Discarding events not consumed within {} ms", WORKER_SHUTDOWN_TIMEOUT_MS);
    }
    if (executor == null) {
      disruptor.halt();
      return;
    }
    executor.shutdown();
    if (!awaitTermination(executor, disruptor::halt)) {
      LOGGER.warn("Workers did not stop within {} ms", WORKER_SHUTDOWN_TIMEOUT_MS);
    }
  }

  /** Shuts the executor down and waits a bounded time for the tasks it runs to return. */
  protected static void awaitWorkers(final ExecutorService executor) {
    executor.shutdown();
    if (!awaitTermination(executor, () -> {})) {
      LOGGER.warn("Workers did not stop within {} ms", WORKER_SHUTDOWN_TIMEOUT_MS);
    }
  }

  private static boolean awaitTermination(
      final ExecutorService executor, final Runnable beforeEachWait) {
    final long deadline =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WORKER_SHUTDOWN_TIMEOUT_MS);
    boolean interrupted = false;
    try {
      while (true) {
        beforeEachWait.run();
        try {
          if (executor.awaitTermination(10, TimeUnit.MILLISECONDS)) {
            return true;
          }
        } catch (final InterruptedException e) {
          // Keep waiting: giving up early would leave the workers running.
          interrupted = true;
        }
        if (System.nanoTime() - deadline >= 0) {
          return false;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
