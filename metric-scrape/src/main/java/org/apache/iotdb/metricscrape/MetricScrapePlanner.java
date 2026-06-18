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

import org.apache.iotdb.metricscrape.MetricScrapeConfig.RelabelConfig;
import org.apache.iotdb.metricscrape.MetricScrapeConfig.ScrapeConfig;
import org.apache.iotdb.metricscrape.MetricScrapeConfig.StaticConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MetricScrapePlanner {

  public List<MetricScrapeTarget> buildTargets(MetricScrapeConfig config) {
    List<MetricScrapeTarget> result = new ArrayList<>();
    for (ScrapeConfig scrapeConfig : config.getScrapeConfigs()) {
      for (StaticConfig staticConfig : scrapeConfig.getStaticConfigs()) {
        for (String target : staticConfig.getTargets()) {
          Map<String, String> labels = new LinkedHashMap<>();
          labels.put("job_name", scrapeConfig.getJobName());
          labels.put("instance", target);
          labels.putAll(staticConfig.getLabels());
          for (RelabelConfig relabelConfig : scrapeConfig.getRelabelConfigs()) {
            labels.put(relabelConfig.getTargetLabel(), relabelConfig.getReplacement());
          }
          result.add(
              new MetricScrapeTarget(
                  scrapeConfig.getJobName(),
                  buildUrl(scrapeConfig, target),
                  scrapeConfig.getScrapeInterval(),
                  scrapeConfig.getScrapeTimeout(),
                  labels));
        }
      }
    }
    return result;
  }

  private String buildUrl(ScrapeConfig scrapeConfig, String target) {
    if (target.startsWith("http://") || target.startsWith("https://")) {
      return appendMetricsPath(target, scrapeConfig.getMetricsPath());
    }
    return scrapeConfig.getScheme() + "://" + target + scrapeConfig.getMetricsPath();
  }

  private String appendMetricsPath(String target, String metricsPath) {
    if (target.endsWith(metricsPath)) {
      return target;
    }
    if (target.endsWith("/")) {
      return target.substring(0, target.length() - 1) + metricsPath;
    }
    return target + metricsPath;
  }
}
