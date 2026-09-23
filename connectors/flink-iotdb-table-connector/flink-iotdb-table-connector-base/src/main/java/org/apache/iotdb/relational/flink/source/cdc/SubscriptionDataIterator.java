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

package org.apache.iotdb.relational.flink.source.cdc;

import org.apache.iotdb.relational.flink.source.common.IoTDBDataIterator;
import org.apache.iotdb.session.subscription.payload.SubscriptionRecordHandler.SubscriptionResultSet;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/** Adapts a subscription {@link SubscriptionResultSet} to the unified {@link IoTDBDataIterator}. */
public class SubscriptionDataIterator implements IoTDBDataIterator {

  private final SubscriptionResultSet resultSet;

  public SubscriptionDataIterator(SubscriptionResultSet resultSet) {
    this.resultSet = resultSet;
  }

  @Override
  public boolean next() throws IOException {
    return resultSet.next();
  }

  @Override
  public List<String> getColumnNames() {
    return resultSet.getColumnNames();
  }

  @Override
  public boolean isNull(int columnIndex) {
    return resultSet.isNull(columnIndex);
  }

  @Override
  public boolean getBoolean(int columnIndex) {
    return resultSet.getBoolean(columnIndex);
  }

  @Override
  public int getInt(int columnIndex) {
    return resultSet.getInt(columnIndex);
  }

  @Override
  public long getLong(int columnIndex) {
    return resultSet.getLong(columnIndex);
  }

  @Override
  public float getFloat(int columnIndex) {
    return resultSet.getFloat(columnIndex);
  }

  @Override
  public double getDouble(int columnIndex) {
    return resultSet.getDouble(columnIndex);
  }

  @Override
  public String getString(int columnIndex) {
    return resultSet.getString(columnIndex);
  }

  @Override
  public byte[] getBinary(int columnIndex) {
    return resultSet.getBinary(columnIndex);
  }

  @Override
  public LocalDate getDate(int columnIndex) {
    return resultSet.getDate(columnIndex);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) {
    return new Timestamp(resultSet.getLong(columnIndex));
  }
}
