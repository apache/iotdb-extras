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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MetricScrapeConfig {

  private final GlobalConfig global;
  private final List<ScrapeConfig> scrapeConfigs;

  public MetricScrapeConfig(GlobalConfig global, List<ScrapeConfig> scrapeConfigs) {
    this.global = global;
    this.scrapeConfigs = copyList(scrapeConfigs);
  }

  public GlobalConfig getGlobal() {
    return global;
  }

  public List<ScrapeConfig> getScrapeConfigs() {
    return scrapeConfigs;
  }

  public static class GlobalConfig {
    private final Duration scrapeInterval;
    private final Duration scrapeTimeout;
    private final String databaseName;
    private final String username;
    private final String password;
    private final List<String> nodeUrls;
    private final int writeBatchSize;

    public GlobalConfig(
        Duration scrapeInterval,
        Duration scrapeTimeout,
        String databaseName,
        String username,
        String password,
        List<String> nodeUrls,
        int writeBatchSize) {
      this.scrapeInterval = scrapeInterval;
      this.scrapeTimeout = scrapeTimeout;
      this.databaseName = databaseName;
      this.username = username;
      this.password = password;
      this.nodeUrls = copyList(nodeUrls);
      this.writeBatchSize = writeBatchSize;
    }

    public Duration getScrapeInterval() {
      return scrapeInterval;
    }

    public Duration getScrapeTimeout() {
      return scrapeTimeout;
    }

    public String getDatabaseName() {
      return databaseName;
    }

    public String getUsername() {
      return username;
    }

    public String getPassword() {
      return password;
    }

    public List<String> getNodeUrls() {
      return nodeUrls;
    }

    public int getWriteBatchSize() {
      return writeBatchSize;
    }
  }

  public static class ScrapeConfig {
    private final String jobName;
    private final String scheme;
    private final String metricsPath;
    private final Duration scrapeInterval;
    private final Duration scrapeTimeout;
    private final List<StaticConfig> staticConfigs;
    private final List<RelabelConfig> relabelConfigs;

    public ScrapeConfig(
        String jobName,
        String scheme,
        String metricsPath,
        Duration scrapeInterval,
        Duration scrapeTimeout,
        List<StaticConfig> staticConfigs,
        List<RelabelConfig> relabelConfigs) {
      this.jobName = jobName;
      this.scheme = scheme;
      this.metricsPath = metricsPath;
      this.scrapeInterval = scrapeInterval;
      this.scrapeTimeout = scrapeTimeout;
      this.staticConfigs = copyList(staticConfigs);
      this.relabelConfigs = copyList(relabelConfigs);
    }

    public String getJobName() {
      return jobName;
    }

    public String getScheme() {
      return scheme;
    }

    public String getMetricsPath() {
      return metricsPath;
    }

    public Duration getScrapeInterval() {
      return scrapeInterval;
    }

    public Duration getScrapeTimeout() {
      return scrapeTimeout;
    }

    public List<StaticConfig> getStaticConfigs() {
      return staticConfigs;
    }

    public List<RelabelConfig> getRelabelConfigs() {
      return relabelConfigs;
    }
  }

  public static class StaticConfig {
    private final List<String> targets;
    private final Map<String, String> labels;

    public StaticConfig(List<String> targets, Map<String, String> labels) {
      this.targets = copyList(targets);
      this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    public List<String> getTargets() {
      return targets;
    }

    public Map<String, String> getLabels() {
      return labels;
    }
  }

  public static class RelabelConfig {
    private final String targetLabel;
    private final String replacement;

    public RelabelConfig(String targetLabel, String replacement) {
      this.targetLabel = targetLabel;
      this.replacement = replacement;
    }

    public String getTargetLabel() {
      return targetLabel;
    }

    public String getReplacement() {
      return replacement;
    }
  }

  private static <T> List<T> copyList(List<T> values) {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
