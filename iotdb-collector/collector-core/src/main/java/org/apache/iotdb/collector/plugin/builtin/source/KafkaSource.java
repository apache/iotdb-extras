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

package org.apache.iotdb.collector.plugin.builtin.source;

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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.apache.iotdb.collector.plugin.builtin.source.constant.KafkaSourceConstant.KAFKA_SOURCE_GROUP_ID_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.KafkaSourceConstant.KAFKA_SOURCE_GROUP_ID_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.KafkaSourceConstant.KAFKA_SOURCE_TOPIC_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.KafkaSourceConstant.KAFKA_SOURCE_TOPIC_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.KafkaSourceConstant.KAFKA_SOURCE_URL_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.KafkaSourceConstant.KAFKA_SOURCE_URL_VALUE;

public class KafkaSource extends PushSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaSource.class);

  private ProgressIndex startIndex;
  private int instanceIndex;

  private Thread workerThread;
  private volatile boolean isStarted = false;

  // kafka config
  private String topic;
  private String kafkaServiceURL;
  private String groupId;
  private long offset;

  @Override
  public void validate(PipeParameterValidator validator) throws Exception {}

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

    topic = pipeParameters.getStringOrDefault(KAFKA_SOURCE_TOPIC_KEY, KAFKA_SOURCE_TOPIC_VALUE);
    kafkaServiceURL =
        pipeParameters.getStringOrDefault(KAFKA_SOURCE_URL_KEY, KAFKA_SOURCE_URL_VALUE);
    groupId =
        pipeParameters.getStringOrDefault(KAFKA_SOURCE_GROUP_ID_KEY, KAFKA_SOURCE_GROUP_ID_VALUE);
  }

  @Override
  public void start() throws Exception {
    if (workerThread == null || !workerThread.isAlive()) {
      isStarted = true;
      workerThread = new Thread(this::doWork);
      workerThread.start();
    }
  }

  public void doWork() {
    final Properties props = new Properties();
    props.put("bootstrap.servers", kafkaServiceURL);
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    props.put("group.id", groupId);
    props.put("auto.offset.reset", "none");
    props.put("enable.auto.commit", "false");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      final TopicPartition currentWorkTopicPartition = new TopicPartition(topic, instanceIndex);
      offset = Long.parseLong(startIndex.getProgressInfo().getOrDefault("offset", "0"));

      consumer.assign(Collections.singleton(currentWorkTopicPartition));
      consumer.seek(currentWorkTopicPartition, offset);

      while (isStarted && !Thread.currentThread().isInterrupted()) {
        markPausePosition();

        final ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        for (final ConsumerRecord<String, String> record : records) {
          LOGGER.info(
              "Partition{} consumed offset={} key={} value={}",
              instanceIndex,
              record.offset(),
              record.key(),
              record.value());

          offset = record.offset() + 1;
          TimeUnit.SECONDS.sleep(1);
        }
      }
    } catch (final InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() throws Exception {
    isStarted = false;
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

    return Optional.of(new ProgressIndex(instanceIndex, progressInfo));
  }
}
