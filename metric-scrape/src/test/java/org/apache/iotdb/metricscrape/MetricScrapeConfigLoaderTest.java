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

import org.junit.Test;

import java.nio.file.Paths;
import java.time.Duration;

import static org.junit.Assert.assertEquals;

public class MetricScrapeConfigLoaderTest {

  @Test
  public void loadExampleConfig() throws Exception {
    MetricScrapeConfig config = MetricScrapeConfigLoader.load(Paths.get("conf/metric-scrape.yml"));

    assertEquals(Duration.ofSeconds(15), config.getGlobal().getScrapeInterval());
    assertEquals(Duration.ofSeconds(10), config.getGlobal().getScrapeTimeout());
    assertEquals("metrics", config.getGlobal().getDatabaseName());
    assertEquals(3, config.getScrapeConfigs().size());
    assertEquals("iotdbModel", config.getScrapeConfigs().get(0).getJobName());
    assertEquals("/metrics", config.getScrapeConfigs().get(0).getMetricsPath());
  }
}
