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

package org.apache.iotdb.collector.plugin.builtin.source.kafka;

import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorRuntimeEnvironment;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;

import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.REPORT_TIME_INTERVAL_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.REPORT_TIME_INTERVAL_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.AUTO_OFFSET_RESET_SET;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.BOOLEAN_SET;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_AUTO_OFFSET_RESET_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_AUTO_OFFSET_RESET_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_BOOTSTRAP_SERVERS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_BOOTSTRAP_SERVERS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_ENABLE_AUTO_COMMIT_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_ENABLE_AUTO_COMMIT_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_GROUP_ID_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_GROUP_ID_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_KEY_DESERIALIZER_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_KEY_DESERIALIZER_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_MAX_POLL_RECORDS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_MAX_POLL_RECORDS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_SESSION_TIMEOUT_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_SESSION_TIMEOUT_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_TOPIC_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_TOPIC_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_VALUE_DESERIALIZER_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.kafka.KafkaSourceConstant.KAFKA_SOURCE_VALUE_DESERIALIZER_KEY;

public class KafkaSource extends PushSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaSource.class);

  private ProgressIndex startIndex;
  private int instanceIndex;

  private Thread workerThread;
  private volatile boolean isStarted = false;
  private volatile KafkaConsumer<String, String> consumer;

  // kafka config
  private String topic;
  private String bootstrapServers;
  private String groupId;
  private String keyDeserializer;
  private String valueDeserializer;
  private String autoOffsetReset;
  private boolean enableAutoCommit;
  private int sessionTimeoutMs;
  private int maxPollIntervalMs;
  private int maxPollRecords;
  private String partitionAssignmentStrategy;

  private long offset;

  private int reportTimeInterval;

  @Override
  public void validate(PipeParameterValidator validator) throws Exception {
    validateRequiredParam(validator, KAFKA_SOURCE_TOPIC_KEY);
    validateRequiredParam(validator, KAFKA_SOURCE_GROUP_ID_KEY);

    validateParam(
        validator,
        KAFKA_SOURCE_AUTO_OFFSET_RESET_KEY,
        autoOffsetReset -> AUTO_OFFSET_RESET_SET.contains(String.valueOf(autoOffsetReset)),
        KAFKA_SOURCE_AUTO_OFFSET_RESET_DEFAULT_VALUE);

    validateParam(
        validator,
        KAFKA_SOURCE_ENABLE_AUTO_COMMIT_KEY,
        enableAutoCommit -> BOOLEAN_SET.contains(String.valueOf(enableAutoCommit)),
        KAFKA_SOURCE_ENABLE_AUTO_COMMIT_DEFAULT_VALUE);

    validateIntegerParam(validator, KAFKA_SOURCE_SESSION_TIMEOUT_MS_KEY, value -> value > 0);
    validateIntegerParam(validator, KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_KEY, value -> value > 0);
    validateIntegerParam(validator, KAFKA_SOURCE_MAX_POLL_RECORDS_KEY, value -> value > 0);
    validateIntegerParam(validator, REPORT_TIME_INTERVAL_KEY, value -> value > 0);
  }

  private void validateParam(
      final PipeParameterValidator validator,
      final String paramKey,
      final Predicate<Object> validationCondition,
      final String defaultValue) {
    final String paramValue = validator.getParameters().getStringOrDefault(paramKey, defaultValue);

    validator.validate(
        validationCondition::test,
        String.format("%s must be one of %s, but got %s", paramKey, BOOLEAN_SET, paramValue),
        paramValue);
  }

  private void validateRequiredParam(
      final PipeParameterValidator validator, final String paramKey) {
    validator.validate(Objects::nonNull, String.format("%s is required", paramKey));
  }

  private void validateIntegerParam(
      final PipeParameterValidator validator,
      final String paramKey,
      final Predicate<Integer> validationCondition) {
    final int paramValue =
        validator.getParameters().getIntOrDefault(paramKey, Integer.parseInt(paramKey));

    validator.validate(
        value -> validationCondition.test((Integer) value),
        String.format("%s must be > 0, but got %d", paramKey, paramValue),
        paramValue);
  }

  @Override
  public void customize(
      PipeParameters pipeParameters, PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    final CollectorRuntimeEnvironment environment =
        (CollectorRuntimeEnvironment) pipeSourceRuntimeConfiguration.getRuntimeEnvironment();

    final String taskId = environment.getPipeName();
    instanceIndex = environment.getInstanceIndex();
    startIndex =
        RuntimeService.progress().isPresent()
            ? RuntimeService.progress().get().getInstanceProgressIndex(taskId, instanceIndex)
                    == null
                ? new ProgressIndex(instanceIndex, new HashMap<>())
                : RuntimeService.progress().get().getInstanceProgressIndex(taskId, instanceIndex)
            : new ProgressIndex(instanceIndex, new HashMap<>());

    topic =
        pipeParameters.getStringOrDefault(KAFKA_SOURCE_TOPIC_KEY, KAFKA_SOURCE_TOPIC_DEFAULT_VALUE);
    bootstrapServers =
        pipeParameters.getStringOrDefault(
            KAFKA_SOURCE_BOOTSTRAP_SERVERS_KEY, KAFKA_SOURCE_BOOTSTRAP_SERVERS_DEFAULT_VALUE);
    groupId =
        pipeParameters.getStringOrDefault(
            KAFKA_SOURCE_GROUP_ID_KEY, KAFKA_SOURCE_GROUP_ID_DEFAULT_VALUE);
    keyDeserializer =
        pipeParameters.getStringOrDefault(
            KAFKA_SOURCE_KEY_DESERIALIZER_KEY, KAFKA_SOURCE_KEY_DESERIALIZER_DEFAULT_VALUE);
    valueDeserializer =
        pipeParameters.getStringOrDefault(
            KAFKA_SOURCE_VALUE_DESERIALIZER_KEY, KAFKA_SOURCE_VALUE_DESERIALIZER_DEFAULT_VALUE);
    autoOffsetReset =
        pipeParameters.getStringOrDefault(
            KAFKA_SOURCE_AUTO_OFFSET_RESET_KEY, KAFKA_SOURCE_AUTO_OFFSET_RESET_DEFAULT_VALUE);
    enableAutoCommit =
        pipeParameters.getBooleanOrDefault(KAFKA_SOURCE_ENABLE_AUTO_COMMIT_KEY, false);
    sessionTimeoutMs =
        pipeParameters.getIntOrDefault(
            KAFKA_SOURCE_SESSION_TIMEOUT_MS_KEY,
            Integer.parseInt(KAFKA_SOURCE_SESSION_TIMEOUT_MS_DEFAULT_VALUE));
    maxPollRecords =
        pipeParameters.getIntOrDefault(
            KAFKA_SOURCE_MAX_POLL_RECORDS_KEY,
            Integer.parseInt(KAFKA_SOURCE_MAX_POLL_RECORDS_DEFAULT_VALUE));
    maxPollIntervalMs =
        pipeParameters.getIntOrDefault(
            KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_KEY,
            Integer.parseInt(KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_DEFAULT_VALUE));
    partitionAssignmentStrategy =
        pipeParameters.getStringOrDefault(
            KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_KEY,
            KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_DEFAULT_VALUE);
    reportTimeInterval =
        pipeParameters.getIntOrDefault(
            REPORT_TIME_INTERVAL_KEY, Integer.parseInt(REPORT_TIME_INTERVAL_DEFAULT_VALUE));
  }

  @Override
  public void start() throws Exception {
    if (workerThread == null || !workerThread.isAlive()) {
      isStarted = true;

      workerThread = new Thread(this::doWork);
      workerThread.setName("kafka-source-worker-" + instanceIndex);
      workerThread.start();
    }
  }

  public void doWork() {
    initConsumer();

    try {
      final TopicPartition currentWorkTopicPartition = new TopicPartition(topic, instanceIndex);
      offset =
          Long.parseLong(
              startIndex
                  .getProgressInfo()
                  .getOrDefault(
                      "offset",
                      report().isPresent()
                          ? report().get().getProgressInfo().getOrDefault("offset", "0")
                          : "0"));

      consumer.assign(Collections.singleton(currentWorkTopicPartition));
      if (!enableAutoCommit) {
        consumer.seek(currentWorkTopicPartition, offset);
      }

      while (isStarted && !Thread.currentThread().isInterrupted()) {
        markPausePosition();

        processRecords(consumer.poll(Duration.ofMillis(100)));

        if (enableAutoCommit) {
          consumer.commitSync();
        }
      }
    } catch (final WakeupException e) {
      LOGGER.warn("The kafka-consumer-worker-{} wakeup triggered", instanceIndex, e);
    } catch (final Exception e) {
      LOGGER.warn("Error occurred in kafka-consumer-worker-{}", instanceIndex, e);
    } finally {
      if (consumer != null) {
        consumer.close();
        consumer = null;
      }
    }
  }

  private void initConsumer() {
    final Properties props = new Properties();

    props.put(KAFKA_SOURCE_BOOTSTRAP_SERVERS_KEY, bootstrapServers);
    props.put(KAFKA_SOURCE_KEY_DESERIALIZER_KEY, keyDeserializer);
    props.put(KAFKA_SOURCE_VALUE_DESERIALIZER_KEY, valueDeserializer);
    props.put(KAFKA_SOURCE_GROUP_ID_KEY, groupId);
    props.put(KAFKA_SOURCE_AUTO_OFFSET_RESET_KEY, autoOffsetReset);
    props.put(KAFKA_SOURCE_ENABLE_AUTO_COMMIT_KEY, enableAutoCommit);
    props.put(KAFKA_SOURCE_SESSION_TIMEOUT_MS_KEY, sessionTimeoutMs);
    props.put(KAFKA_SOURCE_MAX_POLL_RECORDS_KEY, maxPollRecords);
    props.put(KAFKA_SOURCE_MAX_POLL_INTERVAL_MS_KEY, maxPollIntervalMs);
    props.put(KAFKA_SOURCE_PARTITION_ASSIGN_STRATEGY_KEY, partitionAssignmentStrategy);

    consumer = new KafkaConsumer<>(props);
  }

  private void processRecords(final ConsumerRecords<String, String> records) {
    records.forEach(
        record -> {
          try {
            supplyRecord(record);
          } catch (final Exception e) {
            LOGGER.warn("Failed to process record at offset {}", record.offset(), e);
          }
        });
  }

  private void supplyRecord(final ConsumerRecord<String, String> record) {
    offset = record.offset();

    LOGGER.debug(
        "Consumed record: partition={}, offset={}, key={}, value={}",
        instanceIndex,
        record.offset(),
        record.key(),
        record.value());
  }

  @Override
  public void close() throws Exception {
    isStarted = false;
    if (consumer != null) {
      consumer.wakeup();
    }

    if (workerThread != null) {
      workerThread.interrupt();
      try {
        workerThread.join(1000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      workerThread = null;
    }
  }

  @Override
  public Optional<ProgressIndex> report() {
    final HashMap<String, String> progressInfo = new HashMap<>();
    progressInfo.put("offset", String.valueOf(offset));
    progressInfo.put(REPORT_TIME_INTERVAL_KEY, String.valueOf(reportTimeInterval));

    return Optional.of(new ProgressIndex(instanceIndex, progressInfo));
  }
}
