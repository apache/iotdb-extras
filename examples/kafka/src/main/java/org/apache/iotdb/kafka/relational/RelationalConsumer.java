package org.example.relational;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RelationalConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelationalConsumer.class);
    private static ITableSessionPool tableSessionPool;
    private List<KafkaConsumer<String, String>> consumerList;

    private RelationalConsumer(List<KafkaConsumer<String, String>> consumerList) {
        this.consumerList = consumerList;
        initSessionPool();
    }

    private static void initSessionPool() {
        tableSessionPool =
            new TableSessionPoolBuilder()
                .nodeUrls(Arrays.asList(RelationalConstant.IOTDB_URLS))
                .user(RelationalConstant.IOTDB_USERNAME)
                .password(RelationalConstant.IOTDB_PASSWORD)
                .maxSize(RelationalConstant.SESSION_SIZE)
                .build();
    }

    public static void main(String[] args) {
        List<KafkaConsumer<String, String>> consumerList = new ArrayList<>();
        for (int i = 0; i < RelationalConstant.CONSUMER_THREAD_NUM; i++) {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, RelationalConstant.KAFKA_SERVICE_URL);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.GROUP_ID_CONFIG, RelationalConstant.TOPIC);

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
            consumerList.add(consumer);
            consumer.subscribe(Collections.singleton(RelationalConstant.TOPIC));
        }
        RelationalConsumer consumer = new RelationalConsumer(consumerList);
        initIoTDB();
        consumer.consumeInParallel();
    }

    private static void initIoTDB() {
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

    private static boolean createDatabase(String dbName) {
        try (ITableSession session = tableSessionPool.getSession()) {
            try {
                session.executeNonQueryStatement(String.format("CREATE DATABASE %s", dbName));
            } catch (IoTDBConnectionException | StatementExecutionException e) {
                LOGGER.error("Create Database Error: ", e);
                return false;
            }
        } catch (IoTDBConnectionException e) {
            LOGGER.error("Get Table Session Error: ", e);
            return false;
        }
        return true;
    }

    private static boolean createTable(String[] tableInfo) {
        try (ITableSession session = tableSessionPool.getSession()) {
            String sql = getCreateTableSQL(tableInfo);
            try {
                session.executeNonQueryStatement(sql);
            } catch (IoTDBConnectionException | StatementExecutionException e) {
                LOGGER.error("Create Table Error: ", e);
                return false;
            }
        } catch (IoTDBConnectionException e) {
            LOGGER.error("Get Table Session Error: ", e);
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

    private void consumeInParallel() {
        ExecutorService executor = Executors.newFixedThreadPool(RelationalConstant.CONSUMER_THREAD_NUM);
        for (int i = 0; i < consumerList.size(); i++) {
            RelationalConsumerThread consumerThread = new RelationalConsumerThread(consumerList.get(i), tableSessionPool);
            executor.submit(consumerThread);
        }
    }
}
