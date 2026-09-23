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

package org.apache.iotdb.relational.flink.source.lookup;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.source.common.IoTDBDataIterator;
import org.apache.iotdb.relational.flink.source.common.RowDataDeserializationSchema;
import org.apache.iotdb.relational.flink.source.scan.SessionScanDataIterator;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.DataType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Blocking lookup reader for an IoTDB table-model table.
 *
 * <p>It builds a point query from the lookup keys and converts the result rows back to {@link
 * RowData}. The instance is not thread-safe until {@link #open()} has been called; afterwards the
 * underlying session pool is safe to use from multiple threads.
 */
public class IoTDBLookupReader implements AutoCloseable {

  private final IoTDBOptions options;
  private final DataType rowDataType;
  private final int[] keyIndices;
  private final List<String> fieldNames;
  private final List<DataType> fieldTypes;
  private final RowDataDeserializationSchema deserializer;

  private volatile ITableSessionPool sessionPool;

  public IoTDBLookupReader(IoTDBOptions options, DataType rowDataType, int[] keyIndices) {
    this.options = options;
    this.rowDataType = rowDataType;
    this.keyIndices = keyIndices.clone();
    this.fieldNames = DataType.getFieldNames(rowDataType);
    this.fieldTypes = DataType.getFieldDataTypes(rowDataType);
    this.deserializer = new RowDataDeserializationSchema(rowDataType);
  }

  /** Opens the IoTDB session pool. The database is taken from the connector options. */
  public synchronized void open() {
    if (sessionPool != null) {
      return;
    }
    TableSessionPoolBuilder builder =
        new TableSessionPoolBuilder()
            .nodeUrls(options.getNodeUrls())
            .user(options.getUsername())
            .password(options.getPassword());
    if (options.getDatabase() != null) {
      builder.database(options.getDatabase());
    }
    sessionPool = builder.build();
  }

  /**
   * Runs the lookup query for the given key row and returns the matching rows.
   *
   * @param keyRow row whose fields are the lookup keys, in {@link #keyIndices} order
   * @return matching rows, or an empty list when a key is {@code null}
   */
  public List<RowData> get(RowData keyRow) throws IOException {
    String sql = buildLookupSql(keyRow);
    if (sql == null) {
      return Collections.emptyList();
    }

    ITableSessionPool pool = sessionPool;
    if (pool == null) {
      throw new IOException("IoTDB lookup reader is not open.");
    }

    try (ITableSession session = pool.getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      List<RowData> rows = new ArrayList<>();
      IoTDBDataIterator iterator = new SessionScanDataIterator(dataSet.iterator());
      while (iterator.next()) {
        rows.add(deserializer.deserialize(iterator));
      }
      return rows;
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Failed to execute IoTDB lookup query: " + sql, e);
    }
  }

  /** Builds the lookup SQL, or {@code null} if a key value cannot be represented. */
  String buildLookupSql(RowData keyRow) {
    List<String> predicates = new ArrayList<>(keyIndices.length);
    for (int position = 0; position < keyIndices.length; position++) {
      int fieldIndex = keyIndices[position];
      String literal = IoTDBUtils.renderLiteral(keyRow, position, fieldTypes.get(fieldIndex));
      if (literal == null) {
        return null;
      }
      predicates.add(IoTDBUtils.quoteIdentifier(fieldNames.get(fieldIndex)) + " = " + literal);
    }
    return IoTDBUtils.buildSelectQuery(options.getTable(), rowDataType, predicates, -1);
  }

  @Override
  public synchronized void close() {
    if (sessionPool != null) {
      try {
        sessionPool.close();
      } finally {
        sessionPool = null;
      }
    }
  }
}
