package org.apache.iotdb.iotdbspringbootstartexample;

import org.apache.iotdb.iotdbspringbootstartexample.service.IoTDBService;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringBootIotdbApplicationTests {

    @Autowired
    private IoTDBService iotdbService;

    @Test
    void contextLoads() throws IoTDBConnectionException, StatementExecutionException {
        iotdbService.querySessionPool();
        iotdbService.queryTableSessionPool();
    }

}