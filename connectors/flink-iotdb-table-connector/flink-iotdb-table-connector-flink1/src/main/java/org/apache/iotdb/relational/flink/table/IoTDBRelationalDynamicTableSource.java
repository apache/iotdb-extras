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
import org.apache.iotdb.relational.flink.source.pushdown.IoTDBExpressionVisitor;

import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.Projection;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsLimitPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dynamic table source of the IoTDB relational (table model) Flink connector.
 *
 * <p>Only scan reads are exposed for now. Projection pushdown is supported for top-level fields.
 * Lookup reads are intentionally disabled until their runtime behavior is implemented.
 */
public class IoTDBRelationalDynamicTableSource
    implements ScanTableSource,
        SupportsFilterPushDown,
        SupportsLimitPushDown,
        SupportsProjectionPushDown {

  private final IoTDBOptions options;
  private final ResolvedSchema schema;
  private DataType physicalRowDataType;
  private final List<String> resolvedFilterQueries = new ArrayList<>();
  private long limit = -1L;

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
            limit));
  }

  @Override
  public boolean supportsNestedProjection() {
    return false;
  }

  @Override
  public void applyProjection(int[][] projectedFields, DataType producedDataType) {
    this.physicalRowDataType = Projection.of(projectedFields).project(physicalRowDataType);
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
  public void applyLimit(long limit) {
    this.limit = limit;
  }

  @Override
  public DynamicTableSource copy() {
    IoTDBRelationalDynamicTableSource copy = new IoTDBRelationalDynamicTableSource(options, schema);
    copy.physicalRowDataType = physicalRowDataType;
    copy.resolvedFilterQueries.addAll(resolvedFilterQueries);
    copy.limit = limit;
    return copy;
  }

  @Override
  public String asSummaryString() {
    return "IoTDB Relational Dynamic Table Source";
  }
}
