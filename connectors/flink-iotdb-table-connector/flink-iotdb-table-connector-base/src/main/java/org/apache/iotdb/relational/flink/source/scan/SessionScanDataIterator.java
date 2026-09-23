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

package org.apache.iotdb.relational.flink.source.scan;

import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.relational.flink.source.common.IoTDBDataIterator;

import org.apache.tsfile.utils.Binary;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Adapts a scan {@link SessionDataSet.DataIterator} (1-based) to the unified {@link
 * IoTDBDataIterator} (0-based).
 */
public class SessionScanDataIterator implements IoTDBDataIterator {

  private final SessionDataSet.DataIterator iterator;
  private final List<String> columnNames;

  public SessionScanDataIterator(SessionDataSet.DataIterator iterator) {
    this.iterator = iterator;
    this.columnNames = iterator.getColumnNameList();
  }

  @Override
  public boolean next() throws IOException {
    try {
      return iterator.next();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public List<String> getColumnNames() {
    return columnNames;
  }

  @Override
  public boolean isNull(int columnIndex) throws IOException {
    try {
      return iterator.isNull(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean getBoolean(int columnIndex) throws IOException {
    try {
      return iterator.getBoolean(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public int getInt(int columnIndex) throws IOException {
    try {
      return iterator.getInt(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public long getLong(int columnIndex) throws IOException {
    try {
      return iterator.getLong(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public float getFloat(int columnIndex) throws IOException {
    try {
      return iterator.getFloat(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public double getDouble(int columnIndex) throws IOException {
    try {
      return iterator.getDouble(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public String getString(int columnIndex) throws IOException {
    try {
      return iterator.getString(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public byte[] getBinary(int columnIndex) throws IOException {
    try {
      Binary binary = iterator.getBlob(columnIndex + 1);
      return binary == null ? null : Arrays.copyOf(binary.getValues(), binary.getLength());
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public LocalDate getDate(int columnIndex) throws IOException {
    try {
      return iterator.getDate(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws IOException {
    try {
      return iterator.getTimestamp(columnIndex + 1);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
