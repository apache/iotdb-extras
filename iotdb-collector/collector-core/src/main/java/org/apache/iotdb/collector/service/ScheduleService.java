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

import org.apache.iotdb.collector.schedule.ScheduleJob;
import org.apache.iotdb.collector.schedule.SchedulePushEventJob;
import org.apache.iotdb.collector.schedule.ScheduleReportProgressJob;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class ScheduleService implements IService {

  private static final AtomicReference<SchedulePushEventJob> SCHEDULE_PUSH_EVENT_JOB =
      new AtomicReference<>();
  private static final AtomicReference<ScheduleReportProgressJob> SCHEDULE_REPORT_PROGRESS_JOB =
      new AtomicReference<>();

  @Override
  public void start() {
    SCHEDULE_PUSH_EVENT_JOB.set(new SchedulePushEventJob());
    SCHEDULE_PUSH_EVENT_JOB.get().start();

    SCHEDULE_REPORT_PROGRESS_JOB.set(new ScheduleReportProgressJob());
    SCHEDULE_REPORT_PROGRESS_JOB.get().start();
  }

  public static Optional<SchedulePushEventJob> pushEvent() {
    return Optional.of(SCHEDULE_PUSH_EVENT_JOB.get());
  }

  public static Optional<ScheduleReportProgressJob> reportProgress() {
    return Optional.of(SCHEDULE_REPORT_PROGRESS_JOB.get());
  }

  @Override
  public synchronized void stop() {
    pushEvent().ifPresent(ScheduleJob::stop);
    SCHEDULE_PUSH_EVENT_JOB.set(null);

    reportProgress().ifPresent(ScheduleJob::stop);
    SCHEDULE_REPORT_PROGRESS_JOB.set(null);
  }

  @Override
  public String name() {
    return "ScheduleService";
  }
}
