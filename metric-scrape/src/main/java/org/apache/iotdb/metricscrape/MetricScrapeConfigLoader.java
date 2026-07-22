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

import org.apache.iotdb.metricscrape.MetricScrapeConfig.GlobalConfig;
import org.apache.iotdb.metricscrape.MetricScrapeConfig.RelabelConfig;
import org.apache.iotdb.metricscrape.MetricScrapeConfig.ScrapeConfig;
import org.apache.iotdb.metricscrape.MetricScrapeConfig.StaticConfig;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MetricScrapeConfigLoader {

  private static final Duration DEFAULT_SCRAPE_INTERVAL = Duration.ofSeconds(15);
  private static final Duration DEFAULT_SCRAPE_TIMEOUT = Duration.ofSeconds(10);
  private static final String DEFAULT_METRICS_PATH = "/metrics";
  private static final String DEFAULT_SCHEME = "http";
  private static final int DEFAULT_WRITE_BATCH_SIZE = 1000;

  private MetricScrapeConfigLoader() {}

  public static MetricScrapeConfig load(Path path) throws IOException {
    try (InputStream inputStream = Files.newInputStream(path)) {
      Object loaded = new Yaml().load(inputStream);
      if (!(loaded instanceof Map)) {
        throw new IllegalArgumentException("YAML root should be a map");
      }
      return parseConfig(castMap(loaded, "root"));
    }
  }

  private static MetricScrapeConfig parseConfig(Map<String, Object> root) {
    Map<String, Object> globalMap = castMap(required(root, "global"), "global");
    GlobalConfig global = parseGlobal(globalMap);
    List<ScrapeConfig> scrapeConfigs = parseScrapeConfigs(required(root, "scrape_configs"), global);
    if (scrapeConfigs.isEmpty()) {
      throw new IllegalArgumentException("scrape_configs should not be empty");
    }
    return new MetricScrapeConfig(global, scrapeConfigs);
  }

  private static GlobalConfig parseGlobal(Map<String, Object> globalMap) {
    Duration interval =
        parseDuration(optional(globalMap, "scrape_interval"), DEFAULT_SCRAPE_INTERVAL);
    Duration timeout = parseDuration(optional(globalMap, "scrape_timeout"), DEFAULT_SCRAPE_TIMEOUT);
    String databaseName = requiredString(globalMap, "iotdb_database");
    String username = stringValue(optional(globalMap, "iotdb_username"), "root");
    String password = stringValue(optional(globalMap, "iotdb_password"), "root");
    List<String> nodeUrls = parseStringList(required(globalMap, "iotdb_urls"), "iotdb_urls");
    int batchSize =
        parsePositiveInt(optional(globalMap, "write_batch_size"), DEFAULT_WRITE_BATCH_SIZE);
    return new GlobalConfig(
        interval, timeout, databaseName, username, password, nodeUrls, batchSize);
  }

  private static List<ScrapeConfig> parseScrapeConfigs(Object value, GlobalConfig global) {
    List<Object> configs = castList(value, "scrape_configs");
    List<ScrapeConfig> result = new ArrayList<>();
    for (int i = 0; i < configs.size(); i++) {
      Map<String, Object> map = castMap(configs.get(i), "scrape_configs[" + i + "]");
      String jobName = requiredString(map, "job_name");
      String scheme = stringValue(optional(map, "scheme"), DEFAULT_SCHEME);
      String metricsPath =
          normalizeMetricsPath(stringValue(optional(map, "metrics_path"), DEFAULT_METRICS_PATH));
      Duration interval =
          parseDuration(optional(map, "scrape_interval"), global.getScrapeInterval());
      Duration timeout = parseDuration(optional(map, "scrape_timeout"), global.getScrapeTimeout());
      List<StaticConfig> staticConfigs = parseStaticConfigs(required(map, "static_configs"));
      List<RelabelConfig> relabelConfigs = parseRelabelConfigs(optional(map, "relabel_configs"));
      result.add(
          new ScrapeConfig(
              jobName, scheme, metricsPath, interval, timeout, staticConfigs, relabelConfigs));
    }
    return result;
  }

  private static List<StaticConfig> parseStaticConfigs(Object value) {
    List<Object> configs = castList(value, "static_configs");
    List<StaticConfig> result = new ArrayList<>();
    for (int i = 0; i < configs.size(); i++) {
      Map<String, Object> map = castMap(configs.get(i), "static_configs[" + i + "]");
      List<String> targets = parseStringList(required(map, "targets"), "targets");
      Map<String, String> labels = parseStringMap(optional(map, "labels"));
      result.add(new StaticConfig(targets, labels));
    }
    return result;
  }

  private static List<RelabelConfig> parseRelabelConfigs(Object value) {
    if (value == null) {
      return Collections.emptyList();
    }
    List<Object> configs = castList(value, "relabel_configs");
    List<RelabelConfig> result = new ArrayList<>();
    for (int i = 0; i < configs.size(); i++) {
      Map<String, Object> map = castMap(configs.get(i), "relabel_configs[" + i + "]");
      String targetLabel = requiredString(map, "target_label");
      String replacement = stringValue(optional(map, "replacement"), "");
      result.add(new RelabelConfig(targetLabel, replacement));
    }
    return result;
  }

  private static Object required(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing required config: " + key);
    }
    return value;
  }

  private static Object optional(Map<String, Object> map, String key) {
    return map.get(key);
  }

  private static String requiredString(Map<String, Object> map, String key) {
    String value = stringValue(required(map, key), null);
    if (isBlank(value)) {
      throw new IllegalArgumentException("Config " + key + " should not be empty");
    }
    return value;
  }

  private static String stringValue(Object value, String defaultValue) {
    return value == null ? defaultValue : String.valueOf(value);
  }

  private static String normalizeMetricsPath(String path) {
    if (isBlank(path)) {
      return DEFAULT_METRICS_PATH;
    }
    return path.startsWith("/") ? path : "/" + path;
  }

  private static Duration parseDuration(Object value, Duration defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (text.isEmpty()) {
      return defaultValue;
    }
    long multiplier;
    String number;
    if (text.endsWith("ms")) {
      multiplier = 1;
      number = text.substring(0, text.length() - 2);
    } else if (text.endsWith("s")) {
      multiplier = 1000;
      number = text.substring(0, text.length() - 1);
    } else if (text.endsWith("m")) {
      multiplier = 60_000;
      number = text.substring(0, text.length() - 1);
    } else if (text.endsWith("h")) {
      multiplier = 3_600_000;
      number = text.substring(0, text.length() - 1);
    } else {
      multiplier = 1000;
      number = text;
    }
    try {
      long amount = Long.parseLong(number);
      if (amount <= 0) {
        throw new IllegalArgumentException("Duration should be positive: " + value);
      }
      return Duration.ofMillis(amount * multiplier);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Illegal duration: " + value, e);
    }
  }

  private static int parsePositiveInt(Object value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    int parsed = Integer.parseInt(String.valueOf(value));
    if (parsed <= 0) {
      throw new IllegalArgumentException("Value should be positive: " + value);
    }
    return parsed;
  }

  private static List<String> parseStringList(Object value, String name) {
    List<Object> rawList = castList(value, name);
    List<String> result = new ArrayList<>();
    for (Object item : rawList) {
      String text = String.valueOf(item);
      if (isBlank(text)) {
        throw new IllegalArgumentException(name + " contains empty value");
      }
      result.add(text);
    }
    return result;
  }

  private static Map<String, String> parseStringMap(Object value) {
    if (value == null) {
      return Collections.emptyMap();
    }
    Map<String, Object> rawMap = castMap(value, "labels");
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
      result.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value, String name) {
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException(name + " should be a map");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private static List<Object> castList(Object value, String name) {
    if (!(value instanceof List)) {
      throw new IllegalArgumentException(name + " should be a list");
    }
    return new ArrayList<>((List<?>) value);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
