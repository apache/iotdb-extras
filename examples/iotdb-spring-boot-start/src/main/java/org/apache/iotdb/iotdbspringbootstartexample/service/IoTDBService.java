package org.apache.iotdb.iotdbspringbootstartexample.service;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.isession.pool.SessionDataSetWrapper;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IoTDBService {

    @Autowired
    private ITableSessionPool ioTDBSessionPool;

    @Autowired
    private SessionPool sessionPool;


    public void queryTableSessionPool() throws IoTDBConnectionException, StatementExecutionException {
        ITableSession tableSession = ioTDBSessionPool.getSession();
        final SessionDataSet sessionDataSet = tableSession.executeQueryStatement("select * from power_data_set limit 10");
        while (sessionDataSet.hasNext()) {
            final RowRecord rowRecord = sessionDataSet.next();
            final List<Field> fields = rowRecord.getFields();
            for (Field field : fields) {
                System.out.print(field.getStringValue());
            }
            System.out.println();
        }
    }

    public void querySessionPool() throws IoTDBConnectionException, StatementExecutionException {
        final SessionDataSetWrapper sessionDataSetWrapper = sessionPool.executeQueryStatement("show databases");
        while (sessionDataSetWrapper.hasNext()) {
            final RowRecord rowRecord = sessionDataSetWrapper.next();
            final List<Field> fields = rowRecord.getFields();
            for (Field field : fields) {
                System.out.print(field.getStringValue());
            }
            System.out.println();
        }
    }
}
