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

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.iotdb.rabbitmq.relational.RelationalConstant;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class RabbitMQChannelUtils {

  private RabbitMQChannelUtils() {}

  public static Connection getConnection()
      throws IOException, TimeoutException {
    ConnectionFactory connectionFactory = new ConnectionFactory();
    connectionFactory.setHost(Constant.SERVER_HOST);
    connectionFactory.setPort(Constant.SERVER_PORT);
    connectionFactory.setVirtualHost(Constant.RABBITMQ_VHOST);
    connectionFactory.setUsername(Constant.RABBITMQ_USERNAME);
    connectionFactory.setPassword(Constant.RABBITMQ_PASSWORD);
    connectionFactory.setAutomaticRecoveryEnabled(true);
    connectionFactory.setNetworkRecoveryInterval(10000);
    return connectionFactory.newConnection(Constant.CONNECTION_NAME);
  }

  public static Connection getRelationalConnection()
      throws IOException, TimeoutException {
    ConnectionFactory connectionFactory = new ConnectionFactory();
    connectionFactory.setHost(RelationalConstant.SERVER_HOST);
    connectionFactory.setPort(RelationalConstant.SERVER_PORT);
    connectionFactory.setVirtualHost(RelationalConstant.RABBITMQ_VHOST);
    connectionFactory.setUsername(RelationalConstant.RABBITMQ_USERNAME);
    connectionFactory.setPassword(RelationalConstant.RABBITMQ_PASSWORD);
    connectionFactory.setAutomaticRecoveryEnabled(true);
    connectionFactory.setNetworkRecoveryInterval(10000);
    return connectionFactory.newConnection(RelationalConstant.CONNECTION_NAME);
  }
}
