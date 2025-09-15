package org.example.relational;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.example.ConsumerThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RelationalConsumerThread implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerThread.class);
    private KafkaConsumer<String, String> consumer;
    private ITableSessionPool tableSessionPool;

    public RelationalConsumerThread(KafkaConsumer<String, String> consumer, ITableSessionPool tableSessionPool) {
        this.consumer = consumer;
        this.tableSessionPool = tableSessionPool;
    }

    @Override
    public void run() {
        try {
            do {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                LOGGER.info("Received records: {}", records.count());
                List<String> dataList = new ArrayList<>(records.count());
                for (ConsumerRecord<String, String> consumerRecord : records) {
                    dataList.add(consumerRecord.value());
                }
                insertDataList(dataList);
            } while (true);
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void insertDataList(List<String> dataList) {
        for (String s : dataList) {
            String sql = getInsertValueSQL(s);

            try (ITableSession session = tableSessionPool.getSession()) {
                try {
                    session.executeNonQueryStatement(sql);
                } catch (IoTDBConnectionException | StatementExecutionException e) {
                    LOGGER.error("Insert Values Into Table Error: ", e);
                }
            } catch (IoTDBConnectionException e) {
                LOGGER.error("Get Table Session Error: ", e);
            }
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
