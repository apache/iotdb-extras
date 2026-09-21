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

package org.apache.iotdb.relational.flink.table;

import org.apache.iotdb.relational.flink.cfg.IoTDBOptions;
import org.apache.iotdb.relational.flink.source.IoTDBSource;
import org.apache.iotdb.relational.flink.source.deserializer.RowDataDeserializationSchema;
import org.apache.iotdb.relational.flink.source.lookup.IoTDBAsyncLookupFunction;
import org.apache.iotdb.relational.flink.source.lookup.IoTDBLookupFunction;
import org.apache.iotdb.relational.flink.source.pushdown.AggregateSpec;
import org.apache.iotdb.relational.flink.source.pushdown.IoTDBAggregatePushDownUtils;
import org.apache.iotdb.relational.flink.source.pushdown.IoTDBExpressionVisitor;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;

import org.apache.flink.table.api.TableException;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.connector.source.abilities.SupportsAggregatePushDown;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsLimitPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.connector.source.lookup.AsyncLookupFunctionProvider;
import org.apache.flink.table.connector.source.lookup.LookupFunctionProvider;
import org.apache.flink.table.expressions.AggregateExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dynamic table source of the IoTDB relational (table model) Flink connector.
 *
 * <p>Scan reads are fully implemented. Lookup reads are declared through {@link LookupTableSource}
 * but their runtime behavior is still a stub.
 */
public class IoTDBRelationalDynamicTableSource
    implements ScanTableSource,
        LookupTableSource,
        SupportsFilterPushDown,
        SupportsLimitPushDown,
        SupportsProjectionPushDown,
        SupportsAggregatePushDown {

  private final IoTDBOptions options;
  private final ResolvedSchema schema;
  private DataType physicalRowDataType;
  private final List<String> resolvedFilterQueries = new ArrayList<>();
  private long limit = -1L;
  private AggregateSpec aggregateSpec;

  public IoTDBRelationalDynamicTableSource(IoTDBOptions options, ResolvedSchema schema) {
    this.options = options;
    this.schema = schema;
    this.physicalRowDataType = schema.toPhysicalRowDataType();
  }

  @Override
  public ChangelogMode getChangelogMode() {
    return ChangelogMode.insertOnly();
  }

  @Override
  public ScanRuntimeProvider getScanRuntimeProvider(ScanContext scanContext) {
    return SourceProvider.of(
        new IoTDBSource<>(
            options,
            physicalRowDataType,
            new RowDataDeserializationSchema(physicalRowDataType),
            resolvedFilterQueries,
            limit,
            aggregateSpec));
  }

  @Override
  public LookupRuntimeProvider getLookupRuntimeProvider(LookupContext lookupContext) {
    // Lookup key indices refer to the scan's current row type, i.e. after any projection that has
    // already been pushed into this source.
    DataType lookupRowDataType = physicalRowDataType;
    List<String> fieldNames = DataType.getFieldNames(lookupRowDataType);
    int[][] keys = lookupContext.getKeys();
    int[] keyIndices = new int[keys.length];
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] == null || keys[i].length != 1) {
        throw new TableException("IoTDB lookup supports only top-level lookup keys.");
      }
      int keyIndex = keys[i][0];
      if (keyIndex < 0 || keyIndex >= fieldNames.size()) {
        throw new TableException("Invalid IoTDB lookup key index: " + keyIndex);
      }
      keyIndices[i] = keyIndex;
    }
    if (options.isLookupAsync()) {
      return AsyncLookupFunctionProvider.of(
          new IoTDBAsyncLookupFunction(
              options, lookupRowDataType, keyIndices, options.getLookupThreadSize()));
    }
    return LookupFunctionProvider.of(
        new IoTDBLookupFunction(options, lookupRowDataType, keyIndices));
  }

  @Override
  public boolean supportsNestedProjection() {
    return false;
  }

  @Override
  public void applyProjection(int[][] projectedFields, DataType producedDataType) {
    if (aggregateSpec == null) {
      this.physicalRowDataType = producedDataType;
    }
  }

  @Override
  public Result applyFilters(List<ResolvedExpression> filters) {
    if (filters == null || filters.isEmpty()) {
      return Result.of(Collections.emptyList(), Collections.emptyList());
    }

    List<ResolvedExpression> acceptedFilters = new ArrayList<>();
    List<ResolvedExpression> remainingFilters = new ArrayList<>();
    IoTDBExpressionVisitor expressionVisitor = new IoTDBExpressionVisitor();
    for (ResolvedExpression filter : filters) {
      String filterQuery = filter.accept(expressionVisitor);
      if (filterQuery == null || filterQuery.trim().isEmpty()) {
        remainingFilters.add(filter);
      } else {
        acceptedFilters.add(filter);
        resolvedFilterQueries.add(filterQuery);
      }
    }
    return Result.of(acceptedFilters, remainingFilters);
  }

  @Override
  public boolean applyAggregates(
      List<int[]> groupingSets,
      List<AggregateExpression> aggregateExpressions,
      DataType producedDataType) {
    // Grouping and argument indices refer to the scan's current row type, which is the row type
    // after any projection that has already been pushed into this source.
    AggregateSpec spec =
        IoTDBAggregatePushDownUtils.translate(
            groupingSets, aggregateExpressions, physicalRowDataType, producedDataType);
    if (spec == null) {
      return false;
    }
    this.aggregateSpec = spec;
    this.physicalRowDataType = producedDataType;
    return true;
  }

  @Override
  public void applyLimit(long limit) {
    if (aggregateSpec == null) {
      this.limit = limit;
    }
  }

  @Override
  public DynamicTableSource copy() {
    IoTDBRelationalDynamicTableSource copy = new IoTDBRelationalDynamicTableSource(options, schema);
    copy.physicalRowDataType = physicalRowDataType;
    copy.resolvedFilterQueries.addAll(resolvedFilterQueries);
    copy.limit = limit;
    copy.aggregateSpec = aggregateSpec;
    return copy;
  }

  @Override
  public String asSummaryString() {
    return "IoTDB Relational Dynamic Table Source";
  }

  /**
   * Builds the IoTDB query that this source would execute. Exposed for tests so the pushed-down
   * projection, filters, limit and aggregation can be verified without executing any query.
   */
  String buildQuery() {
    if (aggregateSpec != null) {
      return IoTDBUtils.buildAggregateQuery(
          options.getTable(),
          aggregateSpec.getSelectExpressions(),
          resolvedFilterQueries,
          aggregateSpec.getGroupByExpressions());
    }
    return IoTDBUtils.buildSelectQuery(
        options.getTable(), physicalRowDataType, resolvedFilterQueries, limit);
  }

  AggregateSpec getAggregateSpec() {
    return aggregateSpec;
  }

  List<String> getResolvedFilterQueries() {
    return resolvedFilterQueries;
  }

  long getLimit() {
    return limit;
  }

  DataType getPhysicalRowDataType() {
    return physicalRowDataType;
  }
}
