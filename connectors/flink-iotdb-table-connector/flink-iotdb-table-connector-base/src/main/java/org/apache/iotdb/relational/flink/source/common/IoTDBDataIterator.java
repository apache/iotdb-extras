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

package org.apache.iotdb.relational.flink.source.common;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Unified iterator-style row cursor shared by the scan and CDC sources.
 *
 * <p>The caller advances with {@link #next()} and then reads the current row by column index. The
 * index is always 0-based, regardless of the underlying IoTDB API.
 */
public interface IoTDBDataIterator {

  boolean next() throws IOException;

  List<String> getColumnNames();

  boolean isNull(int columnIndex) throws IOException;

  boolean getBoolean(int columnIndex) throws IOException;

  int getInt(int columnIndex) throws IOException;

  long getLong(int columnIndex) throws IOException;

  float getFloat(int columnIndex) throws IOException;

  double getDouble(int columnIndex) throws IOException;

  String getString(int columnIndex) throws IOException;

  byte[] getBinary(int columnIndex) throws IOException;

  LocalDate getDate(int columnIndex) throws IOException;

  Timestamp getTimestamp(int columnIndex) throws IOException;
}
