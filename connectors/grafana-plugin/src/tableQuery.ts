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
import { TableColumn } from './types';

// quoteIdentifier wraps a name in double quotes, doubling any embedded quote.
// The names come from DESC, so quoting them never changes what they refer to,
// and it keeps the query valid when a name is a reserved word such as "order"
// or "group", or contains a space.
export function quoteIdentifier(name: string): string {
  return `"${name.replace(/"/g, '""')}"`;
}

// Numeric FIELD types. The Time series format plots these; string and boolean
// fields are treated as factors and split each device into extra series.
const numericTypes = ['INT32', 'INT64', 'FLOAT', 'DOUBLE'];

// starterTableQuery builds a first query for a table: the time column, every
// TAG column and the numeric FIELD columns, limited to the dashboard's time
// range. ATTRIBUTE columns are left out because they do not change over time.
// The TAG columns are what the Time series format uses to split the result into
// one series per device. A table with no numeric field, such as the views in
// information_schema, gets all of its fields instead, and a table without a
// time column gets no time filter.
export function starterTableQuery(table: string, columns: TableColumn[]): string {
  const time = columns.find((c) => c.category === 'TIME')?.name;
  const fields = columns.filter((c) => c.category === 'FIELD');
  const numericFields = fields.filter((c) => numericTypes.includes(c.dataType.toUpperCase()));
  const selected = [
    ...(time ? [time] : []),
    ...columns.filter((c) => c.category === 'TAG').map((c) => c.name),
    ...(numericFields.length > 0 ? numericFields : fields).map((c) => c.name),
  ].map(quoteIdentifier);
  const projection = selected.length > 0 ? selected.join(', ') : '*';
  const from = `SELECT ${projection} FROM ${quoteIdentifier(table)}`;
  return time ? `${from} WHERE $__timeFilter(${quoteIdentifier(time)})` : from;
}
