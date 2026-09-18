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

package org.apache.iotdb.relational.flink.catalog;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;

import org.apache.flink.table.api.Schema;
import org.apache.flink.table.catalog.AbstractCatalog;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogDatabase;
import org.apache.flink.table.catalog.CatalogDatabaseImpl;
import org.apache.flink.table.catalog.CatalogFunction;
import org.apache.flink.table.catalog.CatalogPartition;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.DatabaseAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotEmptyException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.catalog.stats.CatalogColumnStatistics;
import org.apache.flink.table.catalog.stats.CatalogTableStatistics;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.types.DataType;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.TableSchema;
import org.apache.tsfile.write.schema.IMeasurementSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flink 1.x Catalog adapter for IoTDB relational tables.
 *
 * <p>Flink Catalog API adaptation belongs here. IoTDB metadata access belongs in {@link
 * IoTDBCatalogClient}; the current implementation supports database/table discovery, table schema
 * resolution, and basic database/table DDL.
 */
public class IoTDBCatalog extends AbstractCatalog {

  private final IoTDBOptions options;
  private final IoTDBCatalogClient catalogClient;

  public IoTDBCatalog(String catalogName, String defaultDatabase, IoTDBOptions options) {
    super(catalogName, defaultDatabase);
    this.options = options;
    this.catalogClient = new IoTDBCatalogClient(options);
  }

  @Override
  public void open() throws CatalogException {
    catalogClient.open();
  }

  @Override
  public void close() throws CatalogException {
    catalogClient.close();
  }

  @Override
  public List<String> listDatabases() {
    return catalogClient.listDatabases();
  }

  @Override
  public CatalogDatabase getDatabase(String databaseName) throws DatabaseNotExistException {
    if (!catalogClient.databaseExists(databaseName)) {
      throw new DatabaseNotExistException(getName(), databaseName);
    }
    return new CatalogDatabaseImpl(Collections.emptyMap(), null);
  }

  @Override
  public boolean databaseExists(String databaseName) {
    return catalogClient.databaseExists(databaseName);
  }

  @Override
  public void createDatabase(String name, CatalogDatabase database, boolean ignoreIfExists)
      throws DatabaseAlreadyExistException, CatalogException {
    if (catalogClient.databaseExists(name)) {
      if (ignoreIfExists) {
        return;
      }
      throw new DatabaseAlreadyExistException(getName(), name);
    }
    catalogClient.createDatabase(name);
  }

  @Override
  public void dropDatabase(String name, boolean ignoreIfNotExists, boolean cascade)
      throws DatabaseNotEmptyException, DatabaseNotExistException, CatalogException {
    if (!catalogClient.databaseExists(name)) {
      if (ignoreIfNotExists) {
        return;
      }
      throw new DatabaseNotExistException(getName(), name);
    }
    if (!cascade && !catalogClient.listTables(name).isEmpty()) {
      throw new DatabaseNotEmptyException(getName(), name);
    }
    catalogClient.dropDatabase(name);
  }

  @Override
  public void alterDatabase(String name, CatalogDatabase newDatabase, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public List<String> listTables(String databaseName) throws DatabaseNotExistException {
    if (!catalogClient.databaseExists(databaseName)) {
      throw new DatabaseNotExistException(getName(), databaseName);
    }
    return catalogClient.listTables(databaseName);
  }

  @Override
  public List<String> listViews(String databaseName) {
    throw unsupported();
  }

  @Override
  public CatalogBaseTable getTable(ObjectPath tablePath) throws TableNotExistException {
    String databaseName = tablePath.getDatabaseName();
    String tableName = tablePath.getObjectName();
    if (!catalogClient.tableExists(databaseName, tableName)) {
      throw new TableNotExistException(getName(), tablePath);
    }
    return toCatalogTable(catalogClient.getTable(databaseName, tableName), databaseName, tableName);
  }

  @Override
  public boolean tableExists(ObjectPath tablePath) {
    return catalogClient.tableExists(tablePath.getDatabaseName(), tablePath.getObjectName());
  }

  @Override
  public void dropTable(ObjectPath tablePath, boolean ignoreIfNotExists)
      throws TableNotExistException, CatalogException {
    if (!catalogClient.tableExists(tablePath.getDatabaseName(), tablePath.getObjectName())) {
      if (ignoreIfNotExists) {
        return;
      }
      throw new TableNotExistException(getName(), tablePath);
    }
    catalogClient.dropTable(tablePath.getDatabaseName(), tablePath.getObjectName());
  }

  @Override
  public void renameTable(ObjectPath tablePath, String newTableName, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public void createTable(ObjectPath tablePath, CatalogBaseTable table, boolean ignoreIfExists)
      throws TableAlreadyExistException, DatabaseNotExistException, CatalogException {
    String databaseName = tablePath.getDatabaseName();
    if (!catalogClient.databaseExists(databaseName)) {
      throw new DatabaseNotExistException(getName(), databaseName);
    }
    if (catalogClient.tableExists(databaseName, tablePath.getObjectName())) {
      if (ignoreIfExists) {
        return;
      }
      throw new TableAlreadyExistException(getName(), tablePath);
    }
    catalogClient.createTable(
        databaseName, tablePath.getObjectName(), toTableSchema(tablePath, table));
  }

  @Override
  public void alterTable(
      ObjectPath tablePath, CatalogBaseTable newTable, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public List<CatalogPartitionSpec> listPartitions(ObjectPath tablePath) {
    throw unsupported();
  }

  @Override
  public List<CatalogPartitionSpec> listPartitions(
      ObjectPath tablePath, CatalogPartitionSpec partitionSpec) {
    throw unsupported();
  }

  @Override
  public List<CatalogPartitionSpec> listPartitionsByFilter(
      ObjectPath tablePath, List<Expression> filters) {
    throw unsupported();
  }

  @Override
  public CatalogPartition getPartition(ObjectPath tablePath, CatalogPartitionSpec partitionSpec) {
    throw unsupported();
  }

  @Override
  public boolean partitionExists(ObjectPath tablePath, CatalogPartitionSpec partitionSpec) {
    throw unsupported();
  }

  @Override
  public void createPartition(
      ObjectPath tablePath,
      CatalogPartitionSpec partitionSpec,
      CatalogPartition partition,
      boolean ignoreIfExists) {
    throw unsupported();
  }

  @Override
  public void dropPartition(
      ObjectPath tablePath, CatalogPartitionSpec partitionSpec, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public void alterPartition(
      ObjectPath tablePath,
      CatalogPartitionSpec partitionSpec,
      CatalogPartition newPartition,
      boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public List<String> listFunctions(String dbName) {
    throw unsupported();
  }

  @Override
  public CatalogFunction getFunction(ObjectPath functionPath) {
    throw unsupported();
  }

  @Override
  public boolean functionExists(ObjectPath functionPath) {
    throw unsupported();
  }

  @Override
  public void createFunction(
      ObjectPath functionPath, CatalogFunction function, boolean ignoreIfExists) {
    throw unsupported();
  }

  @Override
  public void alterFunction(
      ObjectPath functionPath, CatalogFunction newFunction, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public void dropFunction(ObjectPath functionPath, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public CatalogTableStatistics getTableStatistics(ObjectPath tablePath) {
    throw unsupported();
  }

  @Override
  public CatalogColumnStatistics getTableColumnStatistics(ObjectPath tablePath) {
    throw unsupported();
  }

  @Override
  public CatalogTableStatistics getPartitionStatistics(
      ObjectPath tablePath, CatalogPartitionSpec partitionSpec) {
    throw unsupported();
  }

  @Override
  public CatalogColumnStatistics getPartitionColumnStatistics(
      ObjectPath tablePath, CatalogPartitionSpec partitionSpec) {
    throw unsupported();
  }

  @Override
  public void alterTableStatistics(
      ObjectPath tablePath, CatalogTableStatistics tableStatistics, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public void alterTableColumnStatistics(
      ObjectPath tablePath, CatalogColumnStatistics columnStatistics, boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public void alterPartitionStatistics(
      ObjectPath tablePath,
      CatalogPartitionSpec partitionSpec,
      CatalogTableStatistics partitionStatistics,
      boolean ignoreIfNotExists) {
    throw unsupported();
  }

  @Override
  public void alterPartitionColumnStatistics(
      ObjectPath tablePath,
      CatalogPartitionSpec partitionSpec,
      CatalogColumnStatistics partitionColumnStatistics,
      boolean ignoreIfNotExists) {
    throw unsupported();
  }

  private TableSchema toTableSchema(ObjectPath tablePath, CatalogBaseTable table) {
    Schema schema = table.getUnresolvedSchema();
    Map<String, String> tableOptions = table.getOptions();
    String timeColumn = getRequiredTimeColumn(tableOptions);
    Set<String> tagColumns =
        parseColumnNames(tableOptions.get(IoTDBOptions.TAG_COLUMNS.key()), "tag-columns");
    Set<String> attributeColumns =
        parseColumnNames(
            tableOptions.get(IoTDBOptions.ATTRIBUTE_COLUMNS.key()), "attribute-columns");

    List<String> columnNames = new ArrayList<>();
    List<TSDataType> dataTypes = new ArrayList<>();
    Map<String, TSDataType> dataTypesByColumn = new HashMap<>();
    for (Schema.UnresolvedColumn column : schema.getColumns()) {
      if (!(column instanceof Schema.UnresolvedPhysicalColumn)) {
        throw new CatalogException(
            "Only physical columns are supported by the IoTDB table model catalog.");
      }
      Object abstractDataType = ((Schema.UnresolvedPhysicalColumn) column).getDataType();
      if (!(abstractDataType instanceof DataType)) {
        throw new CatalogException(
            "Unresolved column data type is not supported: " + column.getName());
      }

      String columnName = column.getName();
      TSDataType dataType = IoTDBUtils.toIoTDBDataType((DataType) abstractDataType);
      columnNames.add(columnName);
      dataTypes.add(dataType);
      dataTypesByColumn.put(IoTDBUtils.normalizeColumnName(columnName), dataType);
    }
    if (columnNames.isEmpty()) {
      throw new CatalogException("An IoTDB table must contain at least one column.");
    }
    IoTDBUtils.validateColumnCategories(
        timeColumn, tagColumns, attributeColumns, dataTypesByColumn);

    List<ColumnCategory> categories =
        IoTDBUtils.resolveColumnCategories(
            columnNames, timeColumn, tagColumns, attributeColumns);
    return new TableSchema(tablePath.getObjectName(), columnNames, dataTypes, categories);
  }

  private static String getRequiredTimeColumn(Map<String, String> tableOptions) {
    String timeColumn = tableOptions.get(IoTDBOptions.TIME_COLUMN.key());
    if (timeColumn == null || timeColumn.trim().isEmpty()) {
      throw new CatalogException(
          "Table option 'time-column' must specify the IoTDB TIME column for CREATE TABLE.");
    }
    return IoTDBUtils.normalizeColumnName(timeColumn);
  }

  private static Set<String> parseColumnNames(String value, String optionName) {
    Set<String> columnNames = new HashSet<>();
    if (value == null || value.trim().isEmpty()) {
      return columnNames;
    }
    for (String columnName : value.split(",", -1)) {
      if (columnName.trim().isEmpty()) {
        throw new CatalogException(
            "Table option '" + optionName + "' contains an empty column name.");
      }
      String normalizedColumnName = IoTDBUtils.normalizeColumnName(columnName);
      if (!columnNames.add(normalizedColumnName)) {
        throw new CatalogException(
            "Table option '" + optionName + "' contains duplicate column: " + columnName);
      }
    }
    return columnNames;
  }

  private CatalogTable toCatalogTable(
      TableSchema tableSchema, String databaseName, String tableName) {
    Schema.Builder schemaBuilder = Schema.newBuilder();
    List<IMeasurementSchema> columns = tableSchema.getColumnSchemas();
    List<ColumnCategory> categories = tableSchema.getColumnTypes();
    String timeColumn = null;
    List<String> tagColumns = new ArrayList<>();
    List<String> attributeColumns = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      IMeasurementSchema column = columns.get(i);
      schemaBuilder.column(
          column.getMeasurementName(), IoTDBUtils.toFlinkDataType(column.getType()));
      switch (categories.get(i)) {
        case TIME:
          timeColumn = column.getMeasurementName();
          break;
        case TAG:
          tagColumns.add(column.getMeasurementName());
          break;
        case ATTRIBUTE:
          attributeColumns.add(column.getMeasurementName());
          break;
        default:
          break;
      }
    }
    if (timeColumn == null) {
      throw new CatalogException(
          "IoTDB table has no TIME column: " + databaseName + "." + tableName);
    }

    Map<String, String> tableOptions = new HashMap<>();
    tableOptions.put(FactoryUtil.CONNECTOR.key(), IoTDBOptions.IDENTIFIER);
    tableOptions.put(
        IoTDBOptions.NODE_URLS.key(), String.join(",", options.getNodeUrls()));
    tableOptions.put(IoTDBOptions.USER.key(), options.getUsername());
    tableOptions.put(IoTDBOptions.PASSWORD.key(), options.getPassword());
    tableOptions.put(IoTDBOptions.DATABASE.key(), databaseName);
    tableOptions.put(IoTDBOptions.TABLE.key(), tableName);
    tableOptions.put(IoTDBOptions.TIME_COLUMN.key(), timeColumn);
    if (!tagColumns.isEmpty()) {
      tableOptions.put(IoTDBOptions.TAG_COLUMNS.key(), String.join(",", tagColumns));
    }
    if (!attributeColumns.isEmpty()) {
      tableOptions.put(
          IoTDBOptions.ATTRIBUTE_COLUMNS.key(), String.join(",", attributeColumns));
    }

    return CatalogTable.of(schemaBuilder.build(), null, Collections.emptyList(), tableOptions);
  }

  public IoTDBOptions getOptions() {
    return options;
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("Not implemented yet.");
  }
}
