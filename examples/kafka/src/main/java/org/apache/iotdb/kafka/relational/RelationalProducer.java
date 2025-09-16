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

package org.example.relational;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class RelationalProducer {

    private final KafkaProducer<String, String> kafkaProducer;
    private static final Logger LOGGER = LoggerFactory.getLogger(RelationalProducer.class);

    public RelationalProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, RelationalConstant.KAFKA_SERVICE_URL);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        kafkaProducer = new KafkaProducer<>(props);
    }

    public static void main(String[] args) {
        RelationalProducer relationalProducer = new RelationalProducer();
        relationalProducer.produce();
        relationalProducer.close();
    }

    private void produce() {
        for (int i = 0; i < RelationalConstant.ALL_DATA.length; i++) {
            String key = Integer.toString(i);
            try {
                RecordMetadata metadata = kafkaProducer.send(new ProducerRecord<>(RelationalConstant.TOPIC, key, RelationalConstant.ALL_DATA[i])).get();
                LOGGER.info("Sent record(key={} value={}) meta(partition={}, offset={})\n", key, RelationalConstant.ALL_DATA[i], metadata.partition(), metadata.offset());
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
            }
        }
    }

    private void close() {
        kafkaProducer.close();
    }
}
