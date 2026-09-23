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

package org.apache.iotdb.relational.flink.sink;

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;
import org.apache.iotdb.session.TableSessionBuilder;

import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.types.DataType;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.write.record.Tablet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sink writer of the IoTDB relational (table model) Flink connector.
 *
 * <p>The writer owns one IoTDB session and one buffered tablet. For each input element it obtains a
 * {@link SinkDataConverter.Iterator}, appends every produced row to the tablet, and flushes the
 * tablet through {@code ITableSession.insert(Tablet)} when it is full.
 *
 * @param <IN> input record type
 */
public class IoTDBSinkWriter<IN> implements SinkWriter<IN> {

  private static final int DEFAULT_BATCH_SIZE = 1024;

  private final IoTDBOptions options;
  private final DataType physicalRowDataType;
  private final SinkDataConverter<IN> converter;

  private ITableSession session;
  private Tablet buffer;
  private boolean closed;

  private List<String> columnNames;
  private List<TSDataType> columnTsTypes;
  private int[] columnIndexes;
  private int timeColumnIndex;

  public IoTDBSinkWriter(
      IoTDBOptions options, DataType physicalRowDataType, SinkDataConverter<IN> converter)
      throws IOException {
    this.options = options;
    this.physicalRowDataType = physicalRowDataType;
    this.converter = converter;
    open();
  }

  @Override
  public void write(IN element, Context context) throws IOException, InterruptedException {
    ensureOpen();

    try (SinkDataConverter.Iterator iterator = converter.getIterator(element)) {
      while (iterator.next()) {
        if (buffer.getRowSize() >= buffer.getMaxRowNumber()) {
          flushBuffer();
        }
        appendCurrentRow(iterator);
      }
    }
  }

  @Override
  public void flush(boolean endOfInput) throws IOException, InterruptedException {
    ensureOpen();
    flushBuffer();
  }

  @Override
  public void close() throws Exception {
    if (closed) {
      return;
    }
    closed = true;

    Exception failure = null;
    try {
      if (session != null) {
        flushBuffer();
      }
    } catch (Exception e) {
      failure = e;
    }

    try {
      converter.close();
    } catch (Exception e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }

    try {
      if (session != null) {
        session.close();
        session = null;
      }
    } catch (Exception e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  private void open() throws IOException {
    try {
      TableSessionBuilder builder =
          new TableSessionBuilder()
              .nodeUrls(options.getNodeUrls())
              .username(options.getUsername())
              .password(options.getPassword());
      if (options.getDatabase() != null) {
        builder.database(options.getDatabase());
      }
      session = builder.build();
      converter.open();
      initializeTablet();
    } catch (Exception e) {
      closeQuietly();
      throw new IOException("Failed to open IoTDB sink writer.", e);
    }
  }

  private void initializeTablet() throws IOException {
    String tableName = options.getTable();
    if (tableName == null || tableName.trim().isEmpty()) {
      throw new IOException("Table option 'iotdb.table' must specify the IoTDB table name.");
    }

    String rawTimeColumn = options.getTimeColumn();
    if (rawTimeColumn == null || rawTimeColumn.trim().isEmpty()) {
      throw new IOException(
          "Table option 'iotdb.time-column' is required to write into an IoTDB table model table.");
    }

    try {
      List<String> fieldNames = DataType.getFieldNames(physicalRowDataType);
      List<DataType> fieldTypes = DataType.getFieldDataTypes(physicalRowDataType);
      if (fieldNames.isEmpty()) {
        throw new IOException("The IoTDB table sink requires at least one column.");
      }

      List<TSDataType> fieldTsTypes = new ArrayList<>(fieldTypes.size());
      for (DataType fieldType : fieldTypes) {
        fieldTsTypes.add(IoTDBUtils.toIoTDBDataType(fieldType));
      }

      String timeColumn = IoTDBUtils.normalizeColumnName(rawTimeColumn);
      Set<String> tagColumns = normalizeColumns(options.getTagColumns());
      Set<String> attributeColumns = normalizeColumns(options.getAttributeColumns());

      IoTDBUtils.validateColumnCategories(
          timeColumn,
          tagColumns,
          attributeColumns,
          IoTDBUtils.normalizeDataTypesByColumn(fieldNames, fieldTsTypes));

      List<ColumnCategory> categories =
          IoTDBUtils.resolveColumnCategories(fieldNames, timeColumn, tagColumns, attributeColumns);

      List<String> names = new ArrayList<>(fieldNames.size() - 1);
      List<TSDataType> tsTypes = new ArrayList<>(fieldNames.size() - 1);
      List<ColumnCategory> nonTimeCategories = new ArrayList<>(fieldNames.size() - 1);
      List<Integer> indexes = new ArrayList<>(fieldNames.size() - 1);
      int resolvedTimeIndex = -1;
      for (int i = 0; i < fieldNames.size(); i++) {
        if (categories.get(i) == ColumnCategory.TIME) {
          resolvedTimeIndex = i;
          continue;
        }
        names.add(fieldNames.get(i));
        tsTypes.add(fieldTsTypes.get(i));
        nonTimeCategories.add(categories.get(i));
        indexes.add(i);
      }
      if (resolvedTimeIndex < 0) {
        throw new IOException(
            "The IoTDB TIME column '" + rawTimeColumn + "' does not exist in the sink schema.");
      }

      this.timeColumnIndex = resolvedTimeIndex;
      this.columnNames = Collections.unmodifiableList(names);
      this.columnTsTypes = Collections.unmodifiableList(tsTypes);
      this.columnIndexes = indexes.stream().mapToInt(Integer::intValue).toArray();
      this.buffer =
          new Tablet(
              tableName,
              columnNames,
              columnTsTypes,
              Collections.unmodifiableList(nonTimeCategories),
              DEFAULT_BATCH_SIZE);
      if (buffer.getMaxRowNumber() <= 0) {
        throw new IOException("Tablet has an invalid max row number.");
      }
    } catch (CatalogException e) {
      throw new IOException("Invalid IoTDB table sink schema: " + e.getMessage(), e);
    }
  }

  private void appendCurrentRow(SinkDataConverter.Iterator iterator) throws IOException {
    if (iterator.isNull(timeColumnIndex)) {
      throw new IOException(
          "The IoTDB TIME column '"
              + options.getTimeColumn()
              + "' must not be null for INSERT records.");
    }

    int row = buffer.getRowSize();
    buffer.addTimestamp(row, iterator.getTimestamp(timeColumnIndex));
    for (int i = 0; i < columnNames.size(); i++) {
      buffer.addValue(
          columnNames.get(i), row, readValue(iterator, columnIndexes[i], columnTsTypes.get(i)));
    }
  }

  private Object readValue(
      SinkDataConverter.Iterator iterator, int columnIndex, TSDataType dataType)
      throws IOException {
    if (iterator.isNull(columnIndex)) {
      return null;
    }
    switch (dataType) {
      case BOOLEAN:
        return iterator.getBoolean(columnIndex);
      case INT32:
        return iterator.getInt(columnIndex);
      case INT64:
        return iterator.getLong(columnIndex);
      case FLOAT:
        return iterator.getFloat(columnIndex);
      case DOUBLE:
        return iterator.getDouble(columnIndex);
      case TEXT:
      case STRING:
        return iterator.getString(columnIndex);
      case BLOB:
        return iterator.getBlob(columnIndex);
      case DATE:
        return iterator.getDate(columnIndex);
      case TIMESTAMP:
        return iterator.getTimestamp(columnIndex);
      default:
        throw new IOException("Unsupported IoTDB data type for sink: " + dataType);
    }
  }

  private static Set<String> normalizeColumns(List<String> columns) {
    if (columns == null) {
      return Collections.emptySet();
    }
    return columns.stream().map(IoTDBUtils::normalizeColumnName).collect(Collectors.toSet());
  }

  private void ensureOpen() throws IOException {
    if (closed || session == null) {
      throw new IOException("IoTDB sink writer is already closed.");
    }
  }

  private void flushBuffer() throws IOException {
    if (buffer != null && buffer.getRowSize() > 0) {
      insert(buffer);
      buffer.reset();
    }
  }

  private void insert(Tablet tablet) throws IOException {
    try {
      session.insert(tablet);
    } catch (Exception e) {
      throw new IOException("Failed to insert tablet into IoTDB.", e);
    }
  }

  private void closeQuietly() {
    if (session != null) {
      try {
        session.close();
      } catch (Exception ignored) {
        // Preserve the original open failure.
      } finally {
        session = null;
      }
    }
  }
}
