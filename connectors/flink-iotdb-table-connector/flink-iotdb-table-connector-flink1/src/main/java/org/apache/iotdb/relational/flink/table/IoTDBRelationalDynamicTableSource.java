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
import org.apache.iotdb.relational.flink.source.cdc.IoTDBCDCSource;
import org.apache.iotdb.relational.flink.source.common.RowDataDeserializationSchema;
import org.apache.iotdb.relational.flink.source.lookup.IoTDBAsyncLookupFunction;
import org.apache.iotdb.relational.flink.source.lookup.IoTDBLookupFunction;
import org.apache.iotdb.relational.flink.source.scan.IoTDBSource;
import org.apache.iotdb.relational.flink.source.scan.pushdown.AggregateSpec;
import org.apache.iotdb.relational.flink.source.scan.pushdown.IoTDBExpressionVisitor;
import org.apache.iotdb.relational.flink.utils.IoTDBUtils;

import org.apache.flink.configuration.ReadableConfig;
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
import org.apache.flink.table.connector.source.lookup.LookupOptions;
import org.apache.flink.table.connector.source.lookup.PartialCachingAsyncLookupProvider;
import org.apache.flink.table.connector.source.lookup.PartialCachingLookupProvider;
import org.apache.flink.table.connector.source.lookup.cache.DefaultLookupCache;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.expressions.AggregateExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dynamic table source of the IoTDB relational (table model) Flink connector.
 *
 * <p>It supports three read paths:
 *
 * <ul>
 *   <li>bounded scan reads ({@code iotdb.scan.mode=snapshot}, the default) with filter, projection,
 *       limit and aggregate pushdown;
 *   <li>lookup reads, either synchronous or asynchronous ({@code iotdb.lookup.async});
 *   <li>unbounded CDC reads backed by the IoTDB subscription API ({@code iotdb.scan.mode=cdc}).
 * </ul>
 *
 * <p>All rows are emitted as inserts, so the changelog mode is insert-only.
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
  private final ReadableConfig config;
  private DataType physicalRowDataType;
  private final List<String> resolvedFilterQueries = new ArrayList<>();
  private long limit = -1L;
  private AggregateSpec aggregateSpec;

  public IoTDBRelationalDynamicTableSource(
      IoTDBOptions options, ResolvedSchema schema, ReadableConfig config) {
    this.options = options;
    this.schema = schema;
    this.config = config;
    this.physicalRowDataType = schema.toPhysicalRowDataType();
  }

  @Override
  public ChangelogMode getChangelogMode() {
    return ChangelogMode.insertOnly();
  }

  @Override
  public ScanRuntimeProvider getScanRuntimeProvider(ScanContext scanContext) {
    if (options.isCdc()) {
      return SourceProvider.of(
          new IoTDBCDCSource<RowData>(
              options, physicalRowDataType, new RowDataDeserializationSchema(physicalRowDataType)));
    }
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
    LookupOptions.LookupCacheType cacheType = config.get(LookupOptions.CACHE_TYPE);
    if (cacheType == LookupOptions.LookupCacheType.FULL) {
      throw new TableException(
          "The IoTDB connector does not support the lookup FULL cache; use 'partial' or 'none'.");
    }
    boolean cacheEnabled = cacheType == LookupOptions.LookupCacheType.PARTIAL;
    if (options.isLookupAsync()) {
      IoTDBAsyncLookupFunction function =
          new IoTDBAsyncLookupFunction(
              options, lookupRowDataType, keyIndices, options.getLookupThreadSize());
      return cacheEnabled
          ? PartialCachingAsyncLookupProvider.of(function, DefaultLookupCache.fromConfig(config))
          : AsyncLookupFunctionProvider.of(function);
    }
    IoTDBLookupFunction function = new IoTDBLookupFunction(options, lookupRowDataType, keyIndices);
    return cacheEnabled
        ? PartialCachingLookupProvider.of(function, DefaultLookupCache.fromConfig(config))
        : LookupFunctionProvider.of(function);
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

    // The CDC source reads the subscription stream and cannot apply row-level filters.
    if (options.isCdc()) {
      return Result.of(Collections.emptyList(), filters);
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
    // The CDC source cannot aggregate an unbounded subscription stream.
    if (options.isCdc()) {
      return false;
    }
    // Grouping and argument indices refer to the scan's current row type, which is the row type
    // after any projection that has already been pushed into this source.
    AggregateSpec spec =
        IoTDBUtils.translateAggregate(
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
    // Limit pushdown is only an optimization hint; the CDC source ignores it and Flink still
    // enforces the limit itself.
    if (options.isCdc()) {
      return;
    }
    if (aggregateSpec == null) {
      this.limit = limit;
    }
  }

  @Override
  public DynamicTableSource copy() {
    IoTDBRelationalDynamicTableSource copy =
        new IoTDBRelationalDynamicTableSource(options, schema, config);
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
