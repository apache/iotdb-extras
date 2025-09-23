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

package org.apache.iotdb.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** The class is to show how to send data to RabbitMQ. */
public class RabbitMQProducer {

  private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQProducer.class);

  public static void main(String[] args) throws IOException, TimeoutException {
    Connection connection = RabbitMQChannelUtils.getConnection();
    Channel channel = connection.createChannel();
    channel.exchangeDeclare(Constant.TOPIC, BuiltinExchangeType.TOPIC);
    channel.confirmSelect();
    AMQP.BasicProperties basicProperties =
        new AMQP.BasicProperties().builder().deliveryMode(2).contentType("UTF-8").build();
    for (int i = 0; i < Constant.ALL_DATA.length; i++) {
      String key = String.format("%s.%s", "IoTDB", Objects.toString(i));
      channel.queueDeclare(key, true, false, false, null);
      channel.basicPublish(
          Constant.TOPIC, key, false, basicProperties, Constant.ALL_DATA[i].getBytes());
      try {
        if (channel.waitForConfirms()) {
          LOGGER.info(" [x] Sent : {}", Constant.ALL_DATA[i]);
        } else {
          LOGGER.error(" [x] Timed out waiting for confirmation");
        }
      } catch (InterruptedException e) {
        LOGGER.error(" [x] Interrupted while waiting for confirmation");
      }
    }
  }
}
