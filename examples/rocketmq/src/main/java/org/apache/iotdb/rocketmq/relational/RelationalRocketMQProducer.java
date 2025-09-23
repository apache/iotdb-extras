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

package org.apache.iotdb.rocketmq.relational;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class RelationalRocketMQProducer {

  private static final Logger LOGGER = LoggerFactory.getLogger(RelationalRocketMQProducer.class);
  private DefaultMQProducer producer;
  private String producerGroup;
  private String serverAddresses;

  public RelationalRocketMQProducer(String producerGroup, String serverAddresses) {
    this.producerGroup = producerGroup;
    this.serverAddresses = serverAddresses;
    producer = new DefaultMQProducer(producerGroup);
    producer.setNamesrvAddr(serverAddresses);
  }

  public static void main(String[] args)
      throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
    RelationalRocketMQProducer producer =
        new RelationalRocketMQProducer(
            RelationalConstant.PRODUCER_GROUP, RelationalConstant.SERVER_ADDRESS);
    producer.start();
    producer.sendMessage();
    producer.shutdown();
  }

  public void start() throws MQClientException {
    producer.start();
  }

  public void sendMessage()
      throws MQBrokerException, RemotingException, InterruptedException, MQClientException {
    for (String data : RelationalConstant.ALL_DATA) {
      Message msg =
          new Message(
              RelationalConstant.TOPIC, null, null, (data).getBytes(StandardCharsets.UTF_8));
      SendResult sendResult =
          producer.send(
              msg,
              (mqs, msg1, arg) -> {
                Integer id = (Integer) arg;
                int index = id % mqs.size();
                return mqs.get(index);
              },
              RelationalUtils.convertStringToInteger(RelationalUtils.getDatabaseNTable(data)));
      String result = sendResult.toString();
      LOGGER.info(result);
    }
  }

  public void shutdown() {
    producer.shutdown();
  }
}
