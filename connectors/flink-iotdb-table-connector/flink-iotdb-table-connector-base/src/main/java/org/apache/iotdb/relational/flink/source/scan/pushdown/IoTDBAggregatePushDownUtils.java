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

package org.apache.iotdb.relational.flink.source.scan.pushdown;

import org.apache.iotdb.relational.flink.utils.IoTDBUtils;

import org.apache.flink.table.expressions.AggregateExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.types.DataType;
import org.apache.tsfile.enums.TSDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates Flink aggregate pushdown information into a serializable {@link AggregateSpec}.
 *
 * <p>The pushdown is all-or-nothing: any unsupported aggregate, argument or grouping makes this
 * return {@code null} so the whole aggregation stays in Flink.
 *
 * <p>Each aggregate is classified and translated in a single pass from its function class name;
 * grouping columns and aggregate arguments are rendered through {@link IoTDBExpressionVisitor}.
 */
public final class IoTDBAggregatePushDownUtils {

  private IoTDBAggregatePushDownUtils() {}

  public static AggregateSpec translate(
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
      String column = IoTDBUtils.quoteIdentifier(sourceFieldNames.get(index));
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
          translateAggregate(
              aggregateExpressions.get(i), producedFieldTypes.get(producedIndex), visitor);
      if (sql == null) {
        return null;
      }
      selectExpressions.add(sql);
    }

    return new AggregateSpec(selectExpressions, groupByExpressions);
  }

  private static String translateAggregate(
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
      return IoTDBUtils.toIoTDBDataType(dataType);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
