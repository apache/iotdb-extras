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

import com.rabbitmq.client.*;
import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.TableSessionBuilder;
import org.example.RabbitMQChannelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;

public class RelationalRabbitMQConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelationalRabbitMQConsumer.class);
    private static ITableSession tableSession;

    public RelationalRabbitMQConsumer() throws IoTDBConnectionException {
        initIoTDB();
    }

    public static void main(String[] args) throws IOException, TimeoutException, IoTDBConnectionException {
        RelationalRabbitMQConsumer consumer = new RelationalRabbitMQConsumer();
        Connection connection = RabbitMQChannelUtils.getRelationalConnection();
        Channel channel = connection.createChannel();
        AMQP.Queue.DeclareOk declareOk =
            channel.queueDeclare(
                    RelationalConstant.RABBITMQ_CONSUMER_QUEUE, true, false, false, new HashMap<>());
        channel.exchangeDeclare(RelationalConstant.TOPIC, BuiltinExchangeType.TOPIC);
        channel.queueBind(declareOk.getQueue(), RelationalConstant.TOPIC, "IoTDB.#", new HashMap<>());
        DefaultConsumer defaultConsumer =
            new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(
                        String consumerTag,
                        Envelope envelope,
                        AMQP.BasicProperties properties,
                        byte[] body) {
                    String param =
                            consumerTag + ", " + envelope.toString() + ", " + properties.toString();
                    LOGGER.info(param);
                    try {
                        consumer.insert(new String(body));
                    } catch (Exception e) {
                        LOGGER.error(e.getMessage());
                    }
                }
            };
        channel.basicConsume(
                declareOk.getQueue(), true, RelationalConstant.RABBITMQ_CONSUMER_TAG, defaultConsumer);
    }

    private void initIoTDB() throws IoTDBConnectionException {
        tableSession =
            new TableSessionBuilder()
                .nodeUrls(Arrays.asList(RelationalConstant.IOTDB_URLS))
                .username(RelationalConstant.IOTDB_USERNAME)
                .password(RelationalConstant.IOTDB_PASSWORD)
                .build();
        for (String db : RelationalConstant.DATABASES) {
            boolean res = createDatabase(db);
            if (!res) {
                throw new RuntimeException("Create database failed");
            }
        }
        for (String[] tableInfo : RelationalConstant.TABLES) {
            boolean res = createTable(tableInfo);
            if (!res) {
                throw new RuntimeException("Create table failed");
            }
        }
    }

    private boolean createDatabase(String dbName) {
        try {
            tableSession.executeNonQueryStatement(String.format("CREATE DATABASE %s", dbName));
        } catch (IoTDBConnectionException | StatementExecutionException e) {
            LOGGER.error("Create Database Error: ", e);
            return false;
        }
        return true;
    }

    private boolean createTable(String[] tableInfo) {
        String sql = getCreateTableSQL(tableInfo);
        try {
            tableSession.executeNonQueryStatement(sql);
        } catch (IoTDBConnectionException | StatementExecutionException e) {
            LOGGER.error("Create Table Error: ", e);
            return false;
        }
        return true;
    }

    private static String getCreateTableSQL(String[] tableInfo) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE \"").append(tableInfo[0]).append("\".\"").append(tableInfo[1]).append("\" (");

        String[] columnNames = tableInfo[2].split(",");
        String[] columnTypes = tableInfo[3].split(",");
        String[] columnCategories = tableInfo[4].split(",");
        int columnSize = columnNames.length;

        for (int i = 0; i < columnSize; i++) {
            sql.append(columnNames[i]).append(" ");
            sql.append(columnTypes[i]).append(" ");
            sql.append(columnCategories[i]).append(",");
        }
        sql.deleteCharAt(sql.length() - 1);
        sql.append(")");
        return sql.toString();
    }

    private void insert(String data) {
        String sql = getInsertValueSQL(data);
        try {
            tableSession.executeNonQueryStatement(sql);
            LOGGER.info("Insert Success: {}", sql);
        } catch (IoTDBConnectionException | StatementExecutionException e) {
            LOGGER.error("Insert Error: ", e);
        }
    }

    private String getInsertValueSQL(String s) {
        StringBuilder sql = new StringBuilder();
        String[] curDataInfo = s.split(";");
        int valueSetSize = curDataInfo.length - 3;
        String database =  curDataInfo[0];
        String tableName = curDataInfo[1];
        String columnNames = curDataInfo[2];
        sql.append("INSERT INTO \"").append(database).append("\".\"").append(tableName).append("\"(");
        sql.append(columnNames).append(") VALUES ");

        for (int j = 0; j < valueSetSize; j++) {
            String columnValues = curDataInfo[3 + j];
            sql.append("(");
            sql.append(columnValues);
            sql.append("),");
        }
        sql.deleteCharAt(sql.length() - 1);
        return sql.toString();
    }
}
