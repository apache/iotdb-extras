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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts a subscription {@link SubscriptionResultSet} to the unified {@link IoTDBDataIterator}.
 *
 * <p>Projection is applied here: the iterator exposes only the projected columns, in the projected
 * order, and maps them onto the full subscription record. Deserializers can therefore read the
 * columns by their output position without knowing about the projection.
 */
public class SubscriptionDataIterator implements IoTDBDataIterator {

  private final SubscriptionResultSet resultSet;
  private final List<String> columnNames;
  private final int[] sourceIndexes;

  public SubscriptionDataIterator(
      SubscriptionResultSet resultSet, List<String> projectedColumns) {
    this.resultSet = resultSet;
    this.columnNames = Collections.unmodifiableList(new ArrayList<>(projectedColumns));
    this.sourceIndexes = resolveSourceIndexes(resultSet.getColumnNames(), projectedColumns);
  }

  private static int[] resolveSourceIndexes(
      List<String> availableColumns, List<String> projectedColumns) {
    Map<String, Integer> indexByName = new HashMap<>(availableColumns.size());
    for (int i = 0; i < availableColumns.size(); i++) {
      indexByName.put(normalize(availableColumns.get(i)), i);
    }
    int[] indexes = new int[projectedColumns.size()];
    for (int i = 0; i < projectedColumns.size(); i++) {
      Integer index = indexByName.get(normalize(projectedColumns.get(i)));
      if (index == null) {
        throw new IllegalArgumentException(
            "Projected column '"
                + projectedColumns.get(i)
                + "' does not exist in the IoTDB result set.");
      }
      indexes[i] = index;
    }
    return indexes;
  }

  private static String normalize(String columnName) {
    return columnName.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean next() throws IOException {
    return resultSet.next();
  }

  @Override
  public List<String> getColumnNames() {
    return columnNames;
  }

  @Override
  public boolean isNull(int columnIndex) {
    return resultSet.isNull(sourceIndexes[columnIndex]);
  }

  @Override
  public boolean getBoolean(int columnIndex) {
    return resultSet.getBoolean(sourceIndexes[columnIndex]);
  }

  @Override
  public int getInt(int columnIndex) {
    return resultSet.getInt(sourceIndexes[columnIndex]);
  }

  @Override
  public long getLong(int columnIndex) {
    return resultSet.getLong(sourceIndexes[columnIndex]);
  }

  @Override
  public float getFloat(int columnIndex) {
    return resultSet.getFloat(sourceIndexes[columnIndex]);
  }

  @Override
  public double getDouble(int columnIndex) {
    return resultSet.getDouble(sourceIndexes[columnIndex]);
  }

  @Override
  public String getString(int columnIndex) {
    return resultSet.getString(sourceIndexes[columnIndex]);
  }

  @Override
  public byte[] getBinary(int columnIndex) {
    return resultSet.getBinary(sourceIndexes[columnIndex]);
  }

  @Override
  public LocalDate getDate(int columnIndex) {
    return resultSet.getDate(sourceIndexes[columnIndex]);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) {
    return new Timestamp(resultSet.getLong(sourceIndexes[columnIndex]));
  }
}
