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

package org.apache.iotdb.relational.flink.source.cdc.client;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.rpc.subscription.config.TopicConstant;
import org.apache.iotdb.session.subscription.ISubscriptionTableSession;
import org.apache.iotdb.session.subscription.SubscriptionTableSessionBuilder;
import org.apache.iotdb.session.subscription.consumer.ISubscriptionTablePullConsumer;
import org.apache.iotdb.session.subscription.consumer.table.SubscriptionTablePullConsumerBuilder;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Properties;

/** Creates the IoTDB subscription topic and the pull consumer used by the CDC source. */
public final class IoTDBSubscriptionClient {

  private IoTDBSubscriptionClient() {}

  /** Creates the topic if it does not exist, always using the row-level record format. */
  public static void createTopicIfNotExists(IoTDBOptions options) throws Exception {
    List<String> nodeUrls = options.getNodeUrls();
    if (nodeUrls == null || nodeUrls.isEmpty()) {
      throw new IllegalArgumentException("IoTDB 'iotdb.node-urls' must not be empty for CDC.");
    }
    String[] hostPort = splitNodeUrl(nodeUrls.get(0));

    Properties properties = new Properties();
    properties.setProperty(TopicConstant.DATABASE_KEY, options.getDatabase());
    properties.setProperty(TopicConstant.TABLE_KEY, options.getTable());
    properties.setProperty(TopicConstant.MODE_KEY, options.getCdcMode());
    properties.setProperty(TopicConstant.FORMAT_KEY, TopicConstant.FORMAT_RECORD_HANDLER_VALUE);
    setTimeProperty(
        properties,
        TopicConstant.START_TIME_KEY,
        "iotdb.cdc.start-time",
        options.getCdcStartTime());
    setTimeProperty(
        properties, TopicConstant.END_TIME_KEY, "iotdb.cdc.end-time", options.getCdcEndTime());

    try (ISubscriptionTableSession session =
        new SubscriptionTableSessionBuilder()
            .host(hostPort[0])
            .port(Integer.parseInt(hostPort[1]))
            .username(options.getUsername())
            .password(options.getPassword())
            .build()) {
      session.open();
      session.createTopicIfNotExists(options.getCdcTopic(), properties);
    }
  }

  /** Builds a pull consumer bound to the configured consumer group. */
  public static ISubscriptionTablePullConsumer createPullConsumer(IoTDBOptions options) {
    return new SubscriptionTablePullConsumerBuilder()
        .nodeUrls(options.getNodeUrls())
        .username(options.getUsername())
        .password(options.getPassword())
        .consumerGroupId(options.getCdcConsumerGroup())
        .autoCommit(options.isCdcAutoCommit())
        .build();
  }

  private static void setTimeProperty(
      Properties properties, String key, String optionName, String value) {
    if (value == null || value.trim().isEmpty()) {
      return;
    }
    properties.setProperty(key, validateTimeValue(value.trim(), optionName));
  }

  private static String validateTimeValue(String value, String optionName) {
    if ("now".equalsIgnoreCase(value)) {
      return value;
    }
    try {
      Long.parseLong(value);
      return value;
    } catch (NumberFormatException ignored) {
      // Not a raw long timestamp; fall through to the datetime formats.
    }
    if (isIsoDateTime(value)) {
      return value;
    }
    throw new IllegalArgumentException(
        "Invalid value for '"
            + optionName
            + "': "
            + value
            + ". Expected 'now', an ISO datetime (e.g. 2011-12-03T10:15:30), or a raw long"
            + " timestamp in the IoTDB timestamp precision.");
  }

  private static boolean isIsoDateTime(String value) {
    return parseable(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        || parseable(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        || parseable(value, DateTimeFormatter.ISO_LOCAL_DATE)
        || parseable(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  private static boolean parseable(String value, DateTimeFormatter formatter) {
    try {
      formatter.parse(value);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static String[] splitNodeUrl(String nodeUrl) {
    String[] parts = nodeUrl.split(":");
    if (parts.length != 2) {
      throw new IllegalArgumentException(
          "IoTDB node url must be in the format host:port, but was: " + nodeUrl);
    }
    return parts;
  }
}
