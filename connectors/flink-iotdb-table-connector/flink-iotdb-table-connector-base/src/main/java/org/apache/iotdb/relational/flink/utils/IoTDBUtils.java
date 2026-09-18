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

package org.apache.iotdb.relational.flink.utils;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.types.DataType;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared utilities for the IoTDB relational (table model) Flink connector.
 *
 * <p>This class centralizes identifier quoting, Flink/IoTDB data type conversion and the column
 * name/category resolution used by both the Flink catalog (DDL) and the sink serializer.
 */
public final class IoTDBUtils {

  private IoTDBUtils() {}

  /**
   * Quotes a resolved logical identifier using IoTDB's double-quote syntax.
   *
   * <p>The input is treated as a logical name, not as SQL text. Backticks are preserved as part of
   * the identifier and double quotes are escaped by doubling.
   */
  public static String quoteIdentifier(String identifier) {
    if (identifier == null) {
      throw new IllegalArgumentException("Identifier must not be null.");
    }
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  public static TSDataType toIoTDBDataType(DataType dataType) {
    switch (dataType.getLogicalType().getTypeRoot()) {
      case BOOLEAN:
        return TSDataType.BOOLEAN;
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return TSDataType.INT32;
      case BIGINT:
        return TSDataType.INT64;
      case FLOAT:
        return TSDataType.FLOAT;
      case DOUBLE:
        return TSDataType.DOUBLE;
      case CHAR:
      case VARCHAR:
        return TSDataType.STRING;
      case BINARY:
      case VARBINARY:
        return TSDataType.BLOB;
      case DATE:
        return TSDataType.DATE;
      case TIMESTAMP_WITHOUT_TIME_ZONE:
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        return TSDataType.TIMESTAMP;
      default:
        throw new CatalogException("Unsupported Flink data type for IoTDB: " + dataType);
    }
  }

  public static DataType toFlinkDataType(TSDataType dataType) {
    switch (dataType) {
      case BOOLEAN:
        return DataTypes.BOOLEAN();
      case INT32:
        return DataTypes.INT();
      case INT64:
        return DataTypes.BIGINT();
      case FLOAT:
        return DataTypes.FLOAT();
      case DOUBLE:
        return DataTypes.DOUBLE();
      case TEXT:
      case STRING:
        return DataTypes.STRING();
      case BLOB:
        return DataTypes.BYTES();
      case DATE:
        return DataTypes.DATE();
      case TIMESTAMP:
        return DataTypes.TIMESTAMP(3);
      default:
        throw new CatalogException("Unsupported IoTDB data type: " + dataType);
    }
  }

  /** Normalizes a column name for option matching. */
  public static String normalizeColumnName(String columnName) {
    if (columnName == null) {
      throw new CatalogException("Column name must not be null.");
    }
    return columnName.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Builds a lookup from normalized column name to {@link TSDataType}.
   *
   * @param columnNames column names in schema order
   * @param dataTypes data types aligned with {@code columnNames}
   */
  public static Map<String, TSDataType> normalizeDataTypesByColumn(
      List<String> columnNames, List<TSDataType> dataTypes) {
    if (columnNames == null || dataTypes == null || columnNames.size() != dataTypes.size()) {
      throw new CatalogException("Column names and data types must have the same size.");
    }
    Map<String, TSDataType> dataTypesByColumn = new HashMap<>();
    for (int i = 0; i < columnNames.size(); i++) {
      dataTypesByColumn.put(normalizeColumnName(columnNames.get(i)), dataTypes.get(i));
    }
    return dataTypesByColumn;
  }

  /**
   * Validates the {@code time-column}, {@code tag-columns} and {@code attribute-columns} options
   * against an existing table schema.
   *
   * @param timeColumn normalized TIME column name
   * @param tagColumns normalized TAG column names
   * @param attributeColumns normalized ATTRIBUTE column names
   * @param dataTypesByColumn table columns keyed by normalized name
   */
  public static void validateColumnCategories(
      String timeColumn,
      Collection<String> tagColumns,
      Collection<String> attributeColumns,
      Map<String, TSDataType> dataTypesByColumn) {
    if (timeColumn == null || timeColumn.isEmpty()) {
      throw new CatalogException("Table option 'time-column' must specify the IoTDB TIME column.");
    }
    if (tagColumns.contains(timeColumn) || attributeColumns.contains(timeColumn)) {
      throw new CatalogException("The TIME column cannot also be a TAG or ATTRIBUTE column.");
    }
    Set<String> overlappingColumns = new HashSet<>(tagColumns);
    overlappingColumns.retainAll(attributeColumns);
    if (!overlappingColumns.isEmpty()) {
      throw new CatalogException(
          "TAG and ATTRIBUTE columns must not overlap: " + overlappingColumns.iterator().next());
    }
    validateColumnExists(timeColumn, "time-column", dataTypesByColumn);
    for (String columnName : tagColumns) {
      validateColumnExists(columnName, "tag-columns", dataTypesByColumn);
    }
    for (String columnName : attributeColumns) {
      validateColumnExists(columnName, "attribute-columns", dataTypesByColumn);
    }
    if (dataTypesByColumn.get(timeColumn) != TSDataType.TIMESTAMP) {
      throw new CatalogException("The IoTDB TIME column must use the TIMESTAMP data type.");
    }
  }

  /**
   * Resolves the {@link ColumnCategory} of every column, in schema order. The returned list
   * includes exactly one {@link ColumnCategory#TIME} entry.
   *
   * @param columnNames column names in schema order
   * @param timeColumn normalized TIME column name
   * @param tagColumns normalized TAG column names
   * @param attributeColumns normalized ATTRIBUTE column names
   */
  public static List<ColumnCategory> resolveColumnCategories(
      List<String> columnNames,
      String timeColumn,
      Collection<String> tagColumns,
      Collection<String> attributeColumns) {
    List<ColumnCategory> categories = new ArrayList<>(columnNames.size());
    for (String columnName : columnNames) {
      String normalizedColumnName = normalizeColumnName(columnName);
      if (normalizedColumnName.equals(timeColumn)) {
        categories.add(ColumnCategory.TIME);
      } else if (tagColumns.contains(normalizedColumnName)) {
        categories.add(ColumnCategory.TAG);
      } else if (attributeColumns.contains(normalizedColumnName)) {
        categories.add(ColumnCategory.ATTRIBUTE);
      } else {
        categories.add(ColumnCategory.FIELD);
      }
    }
    return categories;
  }

  /**
   * Builds a bounded table-model scan query.
   *
   * @param table IoTDB table name
   * @param rowDataType projected Flink row type whose field names define the SELECT list
   * @param filterQueries already-rendered IoTDB predicate fragments
   * @param limit maximum number of rows, or a negative value for no limit
   * @return IoTDB SELECT SQL
   */
  public static String buildSelectQuery(
      String table, DataType rowDataType, List<String> filterQueries, long limit) {
    List<String> fieldNames = DataType.getFieldNames(rowDataType);
    if (fieldNames.isEmpty()) {
      throw new IllegalArgumentException("IoTDB source requires at least one selected column.");
    }

    StringBuilder columns = new StringBuilder();
    for (String fieldName : fieldNames) {
      if (columns.length() > 0) {
        columns.append(", ");
      }
      columns.append(quoteIdentifier(fieldName));
    }

    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(columns)
            .append(" FROM ")
            .append(quoteIdentifier(table));
    if (filterQueries != null && !filterQueries.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", filterQueries));
    }
    if (limit >= 0) {
      sql.append(" LIMIT ").append(limit);
    }
    return sql.toString();
  }

  private static void validateColumnExists(
      String columnName, String optionName, Map<String, TSDataType> dataTypesByColumn) {
    if (!dataTypesByColumn.containsKey(columnName)) {
      throw new CatalogException(
          "Column '"
              + columnName
              + "' declared by table option '"
              + optionName
              + "' does not exist.");
    }
  }
}
