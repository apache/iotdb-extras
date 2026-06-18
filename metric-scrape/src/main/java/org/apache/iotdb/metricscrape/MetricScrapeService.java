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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricScrapeService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetricScrapeService.class);

  private final MetricScrapeConfig config;
  private final MetricScrapeHttpClient httpClient = new MetricScrapeHttpClient();
  private final PrometheusTextParser parser = new PrometheusTextParser();
  private final MetricScrapePlanner planner = new MetricScrapePlanner();
  private MetricTableWriter writer;
  private ScheduledExecutorService executorService;

  public MetricScrapeService(MetricScrapeConfig config) {
    this.config = config;
  }

  public synchronized void start() throws Exception {
    List<MetricScrapeTarget> targets = planner.buildTargets(config);
    if (targets.isEmpty()) {
      throw new IllegalArgumentException("No metric scrape target configured");
    }
    writer = new MetricTableWriter(config.getGlobal());
    writer.initializeDatabase();
    AtomicInteger threadIndex = new AtomicInteger();
    executorService =
        Executors.newScheduledThreadPool(
            Math.min(targets.size(), Runtime.getRuntime().availableProcessors() * 2),
            runnable -> {
              Thread thread =
                  new Thread(runnable, "metric-scrape-" + threadIndex.incrementAndGet());
              thread.setDaemon(false);
              return thread;
            });
    for (MetricScrapeTarget target : targets) {
      ScheduledExecutorUtil.safelyScheduleWithFixedDelay(
          executorService,
          () -> scrapeTarget(target),
          0,
          target.getInterval().toMillis(),
          TimeUnit.MILLISECONDS);
      LOGGER.info(
          "Scheduled metric target {} with interval {}.", target.getUrl(), target.getInterval());
    }
  }

  public synchronized void stop() {
    if (executorService != null) {
      executorService.shutdownNow();
      executorService = null;
    }
    if (writer != null) {
      writer.close();
      writer = null;
    }
  }

  private void scrapeTarget(MetricScrapeTarget target) {
    try {
      String text =
          httpClient.get(target.getUrl(), Math.toIntExact(target.getTimeout().toMillis()));
      List<PrometheusSample> samples = parser.parse(text, System.currentTimeMillis());
      writer.write(enrichSamples(samples, target));
      LOGGER.debug("Scraped {} samples from {}.", samples.size(), target.getUrl());
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Failed to scrape metric target {}.", target.getUrl(), e);
    } catch (Exception e) {
      LOGGER.warn("Failed to write metric samples from {}.", target.getUrl(), e);
    }
  }

  private List<PrometheusSample> enrichSamples(
      List<PrometheusSample> samples, MetricScrapeTarget target) {
    List<PrometheusSample> result = new ArrayList<>(samples.size());
    for (PrometheusSample sample : samples) {
      result.add(sample.withLabels(mergeLabels(target.getLabels(), sample.getLabels())));
    }
    return result;
  }

  private Map<String, String> mergeLabels(
      Map<String, String> targetLabels, Map<String, String> sampleLabels) {
    java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>(targetLabels);
    for (Map.Entry<String, String> entry : sampleLabels.entrySet()) {
      labels.putIfAbsent(entry.getKey(), entry.getValue());
    }
    return labels;
  }
}
