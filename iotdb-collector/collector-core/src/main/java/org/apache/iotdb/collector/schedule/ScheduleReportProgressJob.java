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

import org.apache.iotdb.collector.config.TaskRuntimeOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledThreadPoolExecutor;

public class ScheduleReportProgressJob extends ScheduleJob {

  private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleReportProgressJob.class);

  public ScheduleReportProgressJob() {
    super(
        new ScheduledThreadPoolExecutor(1),
        TaskRuntimeOptions.TASK_PROGRESS_REPORT_INTERVAL.value());

    LOGGER.info("new single scheduled thread pool: {}", ThreadName.SCHEDULE_REPORT_PROGRESS_JOB);
  }
}
