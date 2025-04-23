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

package org.apache.iotdb.collector.service;

import org.apache.iotdb.collector.config.PipeRuntimeOptions;
import org.apache.iotdb.collector.utils.preiodical.ScheduledExecutorUtil;
import org.apache.iotdb.collector.utils.preiodical.WrappedRunnable;

import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PeriodicalJobService implements IService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PeriodicalJobService.class);

  private static final ScheduledExecutorService executorService =
      new ScheduledThreadPoolExecutor(1);

  private Future<?> executorFuture;

  // String: task id
  // Boolean: task status, if task pause, skip execute
  // WrappedRunnable: periodical job
  private static final List<Pair<String, Pair<Boolean, WrappedRunnable>>> PERIODICAL_JOBS =
      new CopyOnWriteArrayList<>();

  @Override
  public void start() {
    if (executorFuture == null) {

      executorFuture =
          ScheduledExecutorUtil.safelyScheduleWithFixedDelay(
              executorService,
              this::execute,
              PipeRuntimeOptions.EXECUTOR_CRON_HEARTBEAT_EVENT_INTERVAL_SECONDS.value(),
              PipeRuntimeOptions.EXECUTOR_CRON_HEARTBEAT_EVENT_INTERVAL_SECONDS.value(),
              TimeUnit.SECONDS);

      LOGGER.info("Periodical Job Service started successfully.");
    }
  }

  protected void execute() {
    for (final Pair<String, Pair<Boolean, WrappedRunnable>> periodicalJob : PERIODICAL_JOBS) {
      if (periodicalJob.right.left) {
        periodicalJob.right.right.run();
      }
    }
  }

  public static synchronized void register(final String taskId, final Runnable periodicalJob) {
    PERIODICAL_JOBS.add(
        new Pair<>(
            taskId,
            new Pair<>(
                true,
                new WrappedRunnable() {
                  @Override
                  public void runMayThrow() {
                    try {
                      periodicalJob.run();
                    } catch (final Exception e) {
                      LOGGER.warn("Periodical job {} failed.", taskId, e);
                    }
                  }
                })));
  }

  public static synchronized void deregister(final String taskId) {
    PERIODICAL_JOBS.removeIf(pair -> pair.left.equals(taskId));
  }

  public static synchronized void resumeSingleTask(final String taskId) {
    PERIODICAL_JOBS.forEach(
        pair -> {
          if (pair.getLeft().equals(taskId)) {
            pair.right.left = true;
          }
        });
  }

  public static synchronized void pauseSingleTask(final String taskId) {
    PERIODICAL_JOBS.forEach(
        pair -> {
          if (pair.getLeft().equals(taskId)) {
            pair.right.left = false;
          }
        });
  }

  @Override
  public synchronized void stop() {
    if (executorFuture != null) {
      executorFuture.cancel(false);
      executorFuture = null;
      LOGGER.info("Periodical Job Service stopped successfully.");
    }
  }

  @Override
  public String name() {
    return "PeriodicalJobService";
  }
}
