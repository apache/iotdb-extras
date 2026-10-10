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
import { quoteIdentifier, starterTableQuery } from './tableQuery';
import { TableColumn } from './types';

const column = (name: string, category: string, dataType = 'STRING'): TableColumn => ({ name, category, dataType });

describe('quoteIdentifier', () => {
  it('quotes every name, so reserved words and spaces stay valid', () => {
    expect(quoteIdentifier('device_id')).toBe('"device_id"');
    expect(quoteIdentifier('order')).toBe('"order"');
    expect(quoteIdentifier('weather station')).toBe('"weather station"');
  });

  it('doubles an embedded double quote', () => {
    expect(quoteIdentifier('a"b')).toBe('"a""b"');
  });
});

describe('starterTableQuery', () => {
  const columns = [
    column('time', 'TIME', 'TIMESTAMP'),
    column('region', 'TAG'),
    column('model', 'ATTRIBUTE'),
    column('temperature', 'FIELD', 'FLOAT'),
    column('device_id', 'TAG'),
    column('humidity', 'FIELD', 'DOUBLE'),
  ];

  it('selects time, then tags, then fields, and filters on the time range', () => {
    expect(starterTableQuery('weather', columns)).toBe(
      'SELECT "time", "region", "device_id", "temperature", "humidity" FROM "weather" WHERE $__timeFilter("time")'
    );
  });

  it('leaves attribute columns out', () => {
    expect(starterTableQuery('weather', columns)).not.toContain('"model"');
  });

  it('quotes a table name that needs it', () => {
    expect(starterTableQuery('weather station', columns)).toContain('FROM "weather station" WHERE');
  });

  it('leaves the time filter out for a table without a time column', () => {
    const view = [column('database', 'TAG'), column('table_name', 'TAG'), column('status', 'ATTRIBUTE')];
    expect(starterTableQuery('tables', view)).toBe('SELECT "database", "table_name" FROM "tables"');
  });

  it('selects everything when there is no tag or field column to pick', () => {
    expect(starterTableQuery('t', [column('status', 'ATTRIBUTE')])).toBe('SELECT * FROM "t"');
  });

  it('leaves string and boolean fields out when the table has numeric ones', () => {
    const mixed = [
      column('time', 'TIME', 'TIMESTAMP'),
      column('device_id', 'TAG'),
      column('temperature', 'FIELD', 'FLOAT'),
      column('status', 'FIELD', 'STRING'),
      column('online', 'FIELD', 'BOOLEAN'),
      column('count', 'FIELD', 'INT64'),
    ];
    expect(starterTableQuery('weather', mixed)).toBe(
      'SELECT "time", "device_id", "temperature", "count" FROM "weather" WHERE $__timeFilter("time")'
    );
  });

  it('keeps every field when none of them is numeric', () => {
    const text = [column('time', 'TIME', 'TIMESTAMP'), column('message', 'FIELD', 'TEXT')];
    expect(starterTableQuery('log', text)).toBe('SELECT "time", "message" FROM "log" WHERE $__timeFilter("time")');
  });

  it('uses the time column the table declares', () => {
    const renamed = [column('ts', 'TIME', 'TIMESTAMP'), column('v', 'FIELD', 'DOUBLE')];
    expect(starterTableQuery('t', renamed)).toBe('SELECT "ts", "v" FROM "t" WHERE $__timeFilter("ts")');
  });
});
