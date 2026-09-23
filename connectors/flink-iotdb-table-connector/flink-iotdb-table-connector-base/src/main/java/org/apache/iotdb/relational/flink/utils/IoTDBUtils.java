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

import org.apache.iotdb.relational.flink.source.scan.pushdown.AggregateSpec;
import org.apache.iotdb.relational.flink.source.scan.pushdown.IoTDBExpressionVisitor;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.expressions.AggregateExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared utilities for the IoTDB relational (table model) Flink connector.
 *
 * <p>This class centralizes identifier quoting, Flink/IoTDB data type conversion, the column
 * name/category resolution used by both the Flink catalog (DDL) and the sink serializer, the
 * rendering of Flink planner/runtime values as IoTDB SQL literals, and the aggregate pushdown
 * translation.
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
   * Validates the {@code iotdb.time-column}, {@code iotdb.tag-columns} and {@code
   * iotdb.attribute-columns} options against an existing table schema.
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
      throw new CatalogException(
          "Table option 'iotdb.time-column' must specify the IoTDB TIME column.");
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
    validateColumnExists(timeColumn, "iotdb.time-column", dataTypesByColumn);
    for (String columnName : tagColumns) {
      validateColumnExists(columnName, "iotdb.tag-columns", dataTypesByColumn);
    }
    for (String columnName : attributeColumns) {
      validateColumnExists(columnName, "iotdb.attribute-columns", dataTypesByColumn);
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

  /**
   * Builds a table-model aggregation query.
   *
   * @param table IoTDB table name
   * @param selectExpressions already-rendered SELECT expressions (grouping columns and aggregates)
   * @param filterQueries already-rendered IoTDB predicate fragments
   * @param groupByExpressions already-rendered GROUP BY expressions, or {@code null} for a global
   *     aggregation
   * @return IoTDB SELECT SQL
   */
  public static String buildAggregateQuery(
      String table,
      List<String> selectExpressions,
      List<String> filterQueries,
      List<String> groupByExpressions) {
    if (selectExpressions == null || selectExpressions.isEmpty()) {
      throw new IllegalArgumentException("IoTDB aggregate query requires at least one column.");
    }

    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(String.join(", ", selectExpressions))
            .append(" FROM ")
            .append(quoteIdentifier(table));
    if (filterQueries != null && !filterQueries.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", filterQueries));
    }
    if (groupByExpressions != null && !groupByExpressions.isEmpty()) {
      sql.append(" GROUP BY ").append(String.join(", ", groupByExpressions));
    }
    return sql.toString();
  }

  /**
   * Renders a Flink planner literal as an IoTDB SQL literal. Returns {@code null} if the literal
   * type or value cannot be represented in IoTDB SQL.
   */
  public static String renderLiteral(ValueLiteralExpression literal) {
    if (literal == null || literal.isNull()) {
      return null;
    }

    try {
      LogicalTypeRoot typeRoot = literal.getOutputDataType().getLogicalType().getTypeRoot();
      switch (typeRoot) {
        case BOOLEAN:
          return literal.getValueAs(Boolean.class).map(String::valueOf).orElse(null);
        case TINYINT:
          return value(literal.getValueAs(Byte.class));
        case SMALLINT:
          return value(literal.getValueAs(Short.class));
        case INTEGER:
          return value(literal.getValueAs(Integer.class));
        case BIGINT:
          return value(literal.getValueAs(Long.class));
        case FLOAT:
          return literal
              .getValueAs(Float.class)
              .filter(value -> !value.isNaN() && !value.isInfinite())
              .map(String::valueOf)
              .orElse(null);
        case DOUBLE:
          return literal
              .getValueAs(Double.class)
              .filter(value -> !value.isNaN() && !value.isInfinite())
              .map(String::valueOf)
              .orElse(null);
        case CHAR:
        case VARCHAR:
          return literal.getValueAs(String.class).map(IoTDBUtils::quoteString).orElse(null);
        case BINARY:
        case VARBINARY:
          return literal.getValueAs(byte[].class).map(IoTDBUtils::formatBinary).orElse(null);
        case DATE:
          return literal
              .getValueAs(LocalDate.class)
              .map(value -> "CAST('" + value + "' AS DATE)")
              .orElse(null);
        case TIMESTAMP_WITHOUT_TIME_ZONE:
          return literal
              .getValueAs(LocalDateTime.class)
              .map(value -> "CAST('" + value + "' AS TIMESTAMP)")
              .orElse(null);
        case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
          return literal
              .getValueAs(Instant.class)
              .map(value -> "CAST('" + value + "' AS TIMESTAMP)")
              .orElse(null);
        default:
          return null;
      }
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Renders a runtime {@link RowData} field as an IoTDB SQL literal. Returns {@code null} when the
   * value is {@code null} or cannot be represented in IoTDB SQL.
   */
  public static String renderLiteral(RowData row, int position, DataType dataType) {
    if (row == null || row.isNullAt(position) || dataType == null) {
      return null;
    }

    LogicalTypeRoot typeRoot = dataType.getLogicalType().getTypeRoot();
    switch (typeRoot) {
      case BOOLEAN:
        return Boolean.toString(row.getBoolean(position));
      case TINYINT:
        return Byte.toString(row.getByte(position));
      case SMALLINT:
        return Short.toString(row.getShort(position));
      case INTEGER:
        return Integer.toString(row.getInt(position));
      case BIGINT:
        return Long.toString(row.getLong(position));
      case FLOAT:
        float floatValue = row.getFloat(position);
        return isFinite(floatValue) ? Float.toString(floatValue) : null;
      case DOUBLE:
        double doubleValue = row.getDouble(position);
        return isFinite(doubleValue) ? Double.toString(doubleValue) : null;
      case CHAR:
      case VARCHAR:
        return quoteString(row.getString(position).toString());
      case BINARY:
      case VARBINARY:
        byte[] bytes = row.getBinary(position);
        return bytes == null ? null : formatBinary(bytes);
      case DATE:
        return "CAST('" + LocalDate.ofEpochDay(row.getInt(position)) + "' AS DATE)";
      case TIMESTAMP_WITHOUT_TIME_ZONE:
        return "CAST('"
            + getTimestamp(row, position, dataType).toLocalDateTime()
            + "' AS TIMESTAMP)";
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        return "CAST('" + getTimestamp(row, position, dataType).toInstant() + "' AS TIMESTAMP)";
      default:
        return null;
    }
  }

  /**
   * Translates Flink aggregate pushdown information into a serializable {@link AggregateSpec}.
   *
   * <p>The pushdown is all-or-nothing: any unsupported aggregate, argument or grouping makes this
   * return {@code null} so the whole aggregation stays in Flink. Each aggregate is classified and
   * translated in a single pass from its function class name; grouping columns and aggregate
   * arguments are rendered through {@link IoTDBExpressionVisitor}.
   */
  public static AggregateSpec translateAggregate(
      List<int[]> groupingSets,
      List<AggregateExpression> aggregateExpressions,
      DataType sourceRowDataType,
      DataType producedDataType) {
    if (groupingSets == null
        || groupingSets.size() != 1
        || aggregateExpressions == null
        || aggregateExpressions.isEmpty()) {
      return null;
    }

    final List<String> sourceFieldNames;
    final List<DataType> producedFieldTypes;
    try {
      sourceFieldNames = DataType.getFieldNames(sourceRowDataType);
      producedFieldTypes = DataType.getFieldDataTypes(producedDataType);
    } catch (RuntimeException e) {
      return null;
    }

    int[] grouping = groupingSets.get(0);
    if (grouping == null) {
      return null;
    }

    List<String> groupByExpressions = new ArrayList<>(grouping.length);
    List<String> selectExpressions = new ArrayList<>(grouping.length + aggregateExpressions.size());
    for (int index : grouping) {
      if (index < 0 || index >= sourceFieldNames.size()) {
        return null;
      }
      String column = quoteIdentifier(sourceFieldNames.get(index));
      groupByExpressions.add(column);
      selectExpressions.add(column);
    }

    IoTDBExpressionVisitor visitor = new IoTDBExpressionVisitor();
    for (int i = 0; i < aggregateExpressions.size(); i++) {
      int producedIndex = grouping.length + i;
      if (producedIndex >= producedFieldTypes.size()) {
        return null;
      }
      String sql =
          translateAggregateFunction(
              aggregateExpressions.get(i), producedFieldTypes.get(producedIndex), visitor);
      if (sql == null) {
        return null;
      }
      selectExpressions.add(sql);
    }

    return new AggregateSpec(selectExpressions, groupByExpressions);
  }

  private static String translateAggregateFunction(
      AggregateExpression aggregate, DataType producedType, IoTDBExpressionVisitor visitor) {
    if (!isSupportedAggregate(aggregate)) {
      return null;
    }

    Class<?> functionClass = aggregate.getFunctionDefinition().getClass();
    String simpleName = functionClass == null ? null : functionClass.getSimpleName();
    TSDataType outType = toTsDataType(producedType);
    if (simpleName == null || outType == null) {
      return null;
    }

    List<FieldReferenceExpression> args = aggregate.getArgs();
    int argCount = args == null ? 0 : args.size();

    String expression;
    if (simpleName.endsWith("Count1AggFunction")) {
      if (argCount != 0 || !isCountType(outType)) {
        return null;
      }
      expression = "COUNT(*)";
    } else if (simpleName.endsWith("CountAggFunction")) {
      if (argCount != 1 || !isCountType(outType)) {
        return null;
      }
      String argSql = args.get(0).accept(visitor);
      if (argSql == null || toTsDataType(args.get(0).getOutputDataType()) == null) {
        return null;
      }
      expression = "COUNT(" + argSql + ")";
    } else if (simpleName.endsWith("Sum0AggFunction") || simpleName.endsWith("SumAggFunction")) {
      if (argCount != 1 || !isNumeric(outType)) {
        return null;
      }
      String argSql = args.get(0).accept(visitor);
      TSDataType argType = argSql == null ? null : toTsDataType(args.get(0).getOutputDataType());
      if (argType == null || !isNumeric(argType)) {
        return null;
      }
      expression = "SUM(" + argSql + ")";
    } else if (simpleName.endsWith("MaxAggFunction") || simpleName.endsWith("MinAggFunction")) {
      if (argCount != 1) {
        return null;
      }
      String argSql = args.get(0).accept(visitor);
      if (argSql == null || toTsDataType(args.get(0).getOutputDataType()) == null) {
        return null;
      }
      String functionName = simpleName.endsWith("MaxAggFunction") ? "MAX" : "MIN";
      expression = functionName + "(" + argSql + ")";
    } else {
      return null;
    }

    return "CAST(" + expression + " AS " + outType.name() + ")";
  }

  private static boolean isSupportedAggregate(AggregateExpression aggregate) {
    return aggregate != null
        && !aggregate.isDistinct()
        && !aggregate.isApproximate()
        && !aggregate.isIgnoreNulls()
        && !aggregate.getFilterExpression().isPresent();
  }

  private static boolean isNumeric(TSDataType dataType) {
    switch (dataType) {
      case INT32:
      case INT64:
      case FLOAT:
      case DOUBLE:
        return true;
      default:
        return false;
    }
  }

  private static boolean isCountType(TSDataType dataType) {
    return dataType == TSDataType.INT32 || dataType == TSDataType.INT64;
  }

  private static TSDataType toTsDataType(DataType dataType) {
    if (dataType == null) {
      return null;
    }
    try {
      return toIoTDBDataType(dataType);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static TimestampData getTimestamp(RowData row, int position, DataType dataType) {
    int precision = ((TimestampType) dataType.getLogicalType()).getPrecision();
    return row.getTimestamp(position, precision);
  }

  private static boolean isFinite(float value) {
    return !Float.isNaN(value) && !Float.isInfinite(value);
  }

  private static boolean isFinite(double value) {
    return !Double.isNaN(value) && !Double.isInfinite(value);
  }

  private static String value(Optional<?> value) {
    return value.map(Object::toString).orElse(null);
  }

  private static String quoteString(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String formatBinary(byte[] value) {
    StringBuilder builder = new StringBuilder(value.length * 2 + 3);
    builder.append("X'");
    for (byte b : value) {
      builder.append(String.format("%02X", b));
    }
    builder.append("'");
    return builder.toString();
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
