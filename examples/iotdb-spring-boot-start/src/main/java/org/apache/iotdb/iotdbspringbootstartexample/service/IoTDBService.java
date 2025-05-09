/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.iotdbspringbootstartexample.service;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ISessionPool;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.isession.pool.SessionDataSetWrapper;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;

import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IoTDBService {

  @Autowired private ITableSessionPool ioTDBSessionPool;

  @Autowired private ISessionPool sessionPool;

  public void queryTableSessionPool() throws IoTDBConnectionException, StatementExecutionException {
    ITableSession tableSession = ioTDBSessionPool.getSession();
    final SessionDataSet sessionDataSet =
        tableSession.executeQueryStatement("select * from power_data_set limit 10");
    while (sessionDataSet.hasNext()) {
      final RowRecord rowRecord = sessionDataSet.next();
      final List<Field> fields = rowRecord.getFields();
      for (Field field : fields) {
        System.out.print(field.getStringValue());
      }
      System.out.println();
    }
    sessionDataSet.close();
    tableSession.close();
  }

  public void querySessionPool() throws IoTDBConnectionException, StatementExecutionException {
    final SessionDataSetWrapper sessionDataSetWrapper =
        sessionPool.executeQueryStatement("show databases");
    while (sessionDataSetWrapper.hasNext()) {
      final RowRecord rowRecord = sessionDataSetWrapper.next();
      final List<Field> fields = rowRecord.getFields();
      for (Field field : fields) {
        System.out.print(field.getStringValue());
      }
      System.out.println();
    }
    sessionDataSetWrapper.close();
  }
}
