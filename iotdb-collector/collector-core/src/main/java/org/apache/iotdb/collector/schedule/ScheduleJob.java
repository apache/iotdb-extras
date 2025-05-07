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

package org.apache.iotdb.collector.schedule;

import org.apache.iotdb.collector.utils.Triple;
import org.apache.iotdb.collector.utils.preiodical.ScheduledExecutorUtil;
import org.apache.iotdb.collector.utils.preiodical.WrappedRunnable;

import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class ScheduleJob {

  private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleJob.class);

  private final ScheduledExecutorService executorService;
  private final long minIntervalSeconds;

  private long rounds;
  private Future<?> executorFuture;

  private final List<Pair<String, Triple<WrappedRunnable, Boolean, Long>>> periodicalJobs =
      new CopyOnWriteArrayList<>();

  public ScheduleJob(
      final ScheduledExecutorService executorService, final long minIntervalSeconds) {
    this.executorService = executorService;
    this.minIntervalSeconds = minIntervalSeconds;
  }

  public synchronized void start() {
    if (executorFuture == null) {
      rounds = 0;

      executorFuture =
          ScheduledExecutorUtil.safelyScheduleWithFixedDelay(
              executorService,
              this::execute,
              minIntervalSeconds,
              minIntervalSeconds,
              TimeUnit.SECONDS);
      LOGGER.info("Periodical job executor is started successfully.");
    }
  }

  protected void execute() {
    ++rounds;

    for (final Pair<String, Triple<WrappedRunnable, Boolean, Long>> periodicalJob :
        periodicalJobs) {
      if (rounds % periodicalJob.right.right == 0 && periodicalJob.right.middle) {
        periodicalJob.right.left.run();
      }
    }
  }

  public synchronized void register(
      final String id, final Runnable periodicalJob, final long intervalInSeconds) {
    periodicalJobs.add(
        new Pair<>(
            id,
            new Triple<>(
                new WrappedRunnable() {
                  @Override
                  public void runMayThrow() {
                    try {
                      periodicalJob.run();
                    } catch (Exception e) {
                      LOGGER.warn("Periodical job {} failed.", id, e);
                    }
                  }
                },
                true,
                Math.max(intervalInSeconds / minIntervalSeconds, 1))));
    LOGGER.info(
        "Periodical job {} is registered successfully. Interval: {} seconds.",
        id,
        Math.max(intervalInSeconds / minIntervalSeconds, 1) * minIntervalSeconds);
  }

  public synchronized void deregister(final String id) {
    periodicalJobs.removeIf(pair -> pair.left.equals(id));
  }

  public synchronized void pauseSingleJob(final String id) {
    periodicalJobs.forEach(
        pair -> {
          if (pair.left.equals(id)) {
            pair.right.middle = false;
          }
        });
  }

  public synchronized void resumeSingleJob(final String id) {
    periodicalJobs.forEach(
        pair -> {
          if (pair.left.equals(id)) {
            pair.right.middle = true;
          }
        });
  }

  public synchronized void stop() {
    if (executorFuture != null) {
      executorFuture.cancel(false);
      executorFuture = null;

      LOGGER.info("Periodical job executor is stopped successfully.");
    }
  }
}
