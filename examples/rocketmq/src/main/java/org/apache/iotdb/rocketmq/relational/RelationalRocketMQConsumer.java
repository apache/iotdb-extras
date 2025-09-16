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

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.TableSessionBuilder;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class RelationalRocketMQConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelationalRocketMQConsumer.class);
    private ITableSession tableSession;
    private DefaultMQPushConsumer consumer;
    private String producerGroup;
    private String serverAddresses;

    public RelationalRocketMQConsumer(
            String producerGroup,
            String serverAddresses) throws IoTDBConnectionException {
        this.producerGroup = producerGroup;
        this.serverAddresses = serverAddresses;
        this.consumer = new DefaultMQPushConsumer(producerGroup);
        this.consumer.setNamesrvAddr(serverAddresses);
        initIoTDB();
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

    public static void main(String[] args)
            throws MQClientException, IoTDBConnectionException {
        RelationalRocketMQConsumer consumer =
            new RelationalRocketMQConsumer(RelationalConstant.CONSUMER_GROUP, RelationalConstant.SERVER_ADDRESS);
        consumer.prepareConsume();
        consumer.start();
    }

    public void prepareConsume() throws MQClientException {
        consumer.subscribe(RelationalConstant.TOPIC, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.registerMessageListener(
            (MessageListenerOrderly)
                (messages, context) -> {
                    for (MessageExt msg : messages) {
                        LOGGER.info(
                            String.format(
                                "%s Receive New Messages: %s %n",
                                Thread.currentThread().getName(), new String(msg.getBody())));
                        try {
                            insert(new String(msg.getBody()));
                        } catch (Exception e) {
                            LOGGER.error(e.getMessage());
                        }
                    }
                    return ConsumeOrderlyStatus.SUCCESS;
                });
    }

    private void insert(String data) {
        String sql = getInsertValueSQL(data);
        try {
            tableSession.executeNonQueryStatement(sql);
        } catch (IoTDBConnectionException | StatementExecutionException e) {
            LOGGER.error("Insert Values Into Table Error: ", e);
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

    public void start() throws MQClientException {
        consumer.start();
    }
}
