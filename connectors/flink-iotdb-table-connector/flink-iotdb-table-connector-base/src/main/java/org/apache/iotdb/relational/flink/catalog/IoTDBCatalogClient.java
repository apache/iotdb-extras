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

import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;

import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.TableSchema;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.write.schema.IMeasurementSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Common IoTDB catalog metadata access layer.
 *
 * <p>This class implements metadata and basic DDL operations used by the Flink catalog. It talks to
 * IoTDB through the table model session and the following SQL statements:
 *
 * <pre>
 *   SHOW DATABASES
 *   SHOW TABLES FROM &lt;database&gt;
 *   DESC &lt;database&gt;.&lt;table&gt;
 *   CREATE/DROP DATABASE
 *   CREATE/DROP TABLE
 * </pre>
 */
public class IoTDBCatalogClient implements AutoCloseable {

  private static final String COLUMN_DATABASE = "Database";
  private static final String COLUMN_TABLE_NAME = "TableName";
  private static final String COLUMN_COLUMN_NAME = "ColumnName";
  private static final String COLUMN_DATA_TYPE = "DataType";
  private static final String COLUMN_CATEGORY = "Category";

  private final IoTDBOptions options;
  private volatile ITableSessionPool sessionPool;

  public IoTDBCatalogClient(IoTDBOptions options) {
    this.options = options;
  }

  public List<String> listDatabases() {
    return querySingleColumn("SHOW DATABASES", COLUMN_DATABASE);
  }

  public List<String> listTables(String database) {
    return querySingleColumn("SHOW TABLES FROM " + quoteIdentifier(database), COLUMN_TABLE_NAME);
  }

  public boolean databaseExists(String database) {
    return listDatabases().contains(database);
  }

  public boolean tableExists(String database, String table) {
    return databaseExists(database) && listTables(database).contains(table);
  }

  public void createDatabase(String database) {
    executeNonQuery("CREATE DATABASE " + quoteIdentifier(database));
  }

  public void dropDatabase(String database) {
    executeNonQuery("DROP DATABASE " + quoteIdentifier(database));
  }

  public void createTable(String database, String table, TableSchema tableSchema) {
    StringBuilder sql =
        new StringBuilder("CREATE TABLE ")
            .append(quoteIdentifier(database))
            .append(".")
            .append(quoteIdentifier(table))
            .append(" (");
    List<IMeasurementSchema> columns = tableSchema.getColumnSchemas();
    List<ColumnCategory> categories = tableSchema.getColumnTypes();
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      IMeasurementSchema column = columns.get(i);
      sql.append(quoteIdentifier(column.getMeasurementName()))
          .append(" ")
          .append(toSqlDataType(column.getType()))
          .append(" ")
          .append(categories.get(i).name());
    }
    sql.append(")");
    executeNonQuery(sql.toString());
  }

  public void dropTable(String database, String table) {
    executeNonQuery("DROP TABLE " + quoteIdentifier(database) + "." + quoteIdentifier(table));
  }

  public TableSchema getTable(String database, String table) {
    String sql = "DESC " + quoteIdentifier(database) + "." + quoteIdentifier(table);
    try (ITableSession session = getSessionPool().getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      int nameIndex = findColumnIndex(dataSet, COLUMN_COLUMN_NAME, 0);
      int typeIndex = findColumnIndex(dataSet, COLUMN_DATA_TYPE, 1);
      int categoryIndex = findColumnIndex(dataSet, COLUMN_CATEGORY, 2);

      List<String> columnNames = new ArrayList<>();
      List<TSDataType> dataTypes = new ArrayList<>();
      List<ColumnCategory> categories = new ArrayList<>();
      while (dataSet.hasNext()) {
        RowRecord row = dataSet.next();
        String columnName = getString(row, nameIndex);
        String dataTypeName = getString(row, typeIndex);
        String categoryName = getString(row, categoryIndex);
        columnNames.add(columnName);
        dataTypes.add(TSDataType.valueOf(dataTypeName.toUpperCase(Locale.ROOT)));
        categories.add(ColumnCategory.valueOf(categoryName.toUpperCase(Locale.ROOT)));
      }
      return new TableSchema(table, columnNames, dataTypes, categories);
    } catch (CatalogException e) {
      throw e;
    } catch (Exception e) {
      throw new CatalogException("Failed to describe IoTDB table: " + database + "." + table, e);
    }
  }

  public IoTDBOptions getOptions() {
    return options;
  }

  /** Opens the session pool. Called by the owning Flink catalog during {@code Catalog.open()}. */
  public synchronized void open() {
    if (sessionPool != null) {
      return;
    }
    try {
      TableSessionPoolBuilder builder =
          new TableSessionPoolBuilder()
              .nodeUrls(options.getNodeUrls())
              .user(options.getUsername())
              .password(options.getPassword());
      if (options.getDatabase() != null) {
        builder.database(options.getDatabase());
      }
      sessionPool = builder.build();
    } catch (Exception e) {
      throw new CatalogException("Failed to open IoTDB table session pool.", e);
    }
  }

  /** Closes the session pool. Called by the owning Flink catalog during {@code Catalog.close()}. */
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

  private List<String> querySingleColumn(String sql, String columnName) {
    try (ITableSession session = getSessionPool().getSession();
        SessionDataSet dataSet = session.executeQueryStatement(sql)) {
      int columnIndex = findColumnIndex(dataSet, columnName, 0);
      List<String> values = new ArrayList<>();
      while (dataSet.hasNext()) {
        String value = getString(dataSet.next(), columnIndex);
        if (value != null) {
          values.add(value);
        }
      }
      return values;
    } catch (CatalogException e) {
      throw e;
    } catch (Exception e) {
      throw new CatalogException("Failed to execute IoTDB catalog query: " + sql, e);
    }
  }

  private void executeNonQuery(String sql) {
    try (ITableSession session = getSessionPool().getSession()) {
      session.executeNonQueryStatement(sql);
    } catch (CatalogException e) {
      throw e;
    } catch (Exception e) {
      throw new CatalogException("Failed to execute IoTDB catalog statement: " + sql, e);
    }
  }

  private static String toSqlDataType(TSDataType dataType) {
    switch (dataType) {
      case BOOLEAN:
      case INT32:
      case INT64:
      case FLOAT:
      case DOUBLE:
      case TEXT:
      case STRING:
      case BLOB:
      case DATE:
      case TIMESTAMP:
        return dataType.name();
      default:
        throw new CatalogException("Unsupported IoTDB data type: " + dataType);
    }
  }

  private static String quoteIdentifier(String identifier) {
    return IoTDBUtils.quoteIdentifier(identifier);
  }

  private ITableSessionPool getSessionPool() {
    ITableSessionPool pool = sessionPool;
    if (pool == null) {
      throw new CatalogException("IoTDB catalog is not open.");
    }
    return pool;
  }

  private static int findColumnIndex(SessionDataSet dataSet, String columnName, int fallbackIndex) {
    List<String> columnNames = dataSet.getColumnNames();
    if (columnNames != null) {
      for (int i = 0; i < columnNames.size(); i++) {
        if (columnName.equalsIgnoreCase(columnNames.get(i))) {
          return i;
        }
      }
    }
    return fallbackIndex;
  }

  private static String getString(RowRecord row, int index) {
    Field field = row.getField(index);
    return field == null ? null : field.getStringValue();
  }
}
