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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

public class MetricScrapeMain {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetricScrapeMain.class);
  private static final String DEFAULT_CONFIG = "conf/metric-scrape.yml";

  public static void main(String[] args) throws Exception {
    Path configPath = parseConfigPath(args);
    MetricScrapeConfig config = MetricScrapeConfigLoader.load(configPath);
    MetricScrapeService service = new MetricScrapeService(config);
    CountDownLatch stopLatch = new CountDownLatch(1);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  LOGGER.info("Stopping metric scrape service.");
                  service.stop();
                  stopLatch.countDown();
                },
                "metric-scrape-shutdown"));

    service.start();
    LOGGER.info("Metric scrape service started with config {}.", configPath);
    stopLatch.await();
  }

  private static Path parseConfigPath(String[] args) {
    if (args.length == 0) {
      return Paths.get(DEFAULT_CONFIG);
    }
    if (args.length == 2 && ("-c".equals(args[0]) || "--config".equals(args[0]))) {
      return Paths.get(args[1]);
    }
    throw new IllegalArgumentException("Usage: java -jar metric-scrape.jar [-c config.yml]");
  }
}
