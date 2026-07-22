/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.metricscrape;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class MetricScrapeTarget {

  private final String jobName;
  private final String url;
  private final Duration interval;
  private final Duration timeout;
  private final Map<String, String> labels;

  public MetricScrapeTarget(
      String jobName, String url, Duration interval, Duration timeout, Map<String, String> labels) {
    this.jobName = jobName;
    this.url = url;
    this.interval = interval;
    this.timeout = timeout;
    this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
  }

  public String getJobName() {
    return jobName;
  }

  public String getUrl() {
    return url;
  }

  public Duration getInterval() {
    return interval;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public Map<String, String> getLabels() {
    return labels;
  }
}
