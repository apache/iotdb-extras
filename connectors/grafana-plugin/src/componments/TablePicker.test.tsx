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
import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { DataSource } from '../datasource';
import { TablePicker } from './TablePicker';

function fakeDatasource(variables: Record<string, string>) {
  return {
    resolveTemplate: jest.fn((value: string) => variables[value] ?? value),
    getTableDatabases: jest.fn().mockResolvedValue(['db1', 'db2']),
    getTableTables: jest.fn().mockResolvedValue([]),
    getTableColumns: jest.fn().mockResolvedValue([]),
  };
}

function renderPicker(ds: ReturnType<typeof fakeDatasource>, props: Partial<React.ComponentProps<typeof TablePicker>>) {
  const all = {
    datasource: ds as unknown as DataSource,
    refId: 'A',
    database: '',
    table: '',
    onDatabaseChange: jest.fn(),
    onTableChange: jest.fn(),
    onSqlChange: jest.fn(),
    ...props,
  };
  return { ...render(<TablePicker {...all} />), props: all };
}

// jsdom has no IntersectionObserver, which the Select menu's scroll indicators use.
beforeAll(() => {
  (global as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

describe('TablePicker', () => {
  it('lists the tables again when the database variable resolves to another database', async () => {
    const variables: Record<string, string> = { $db: 'db1' };
    const ds = fakeDatasource(variables);
    const { rerender, props } = renderPicker(ds, { database: '$db' });
    await waitFor(() => expect(ds.getTableTables).toHaveBeenCalledWith('db1'));

    variables.$db = 'db2';
    rerender(<TablePicker {...props} />);

    await waitFor(() => expect(ds.getTableTables).toHaveBeenCalledWith('db2'));
  });

  it('keeps a typed database name when the picker loses focus', async () => {
    const ds = fakeDatasource({});
    const { props } = renderPicker(ds, {});
    const databaseInput = document.getElementById('iotdb-table-database-A') as HTMLInputElement;

    await act(async () => {
      fireEvent.focus(databaseInput);
      fireEvent.change(databaseInput, { target: { value: '  $database  ' } });
    });
    await act(async () => {
      fireEvent.blur(databaseInput);
    });

    expect(props.onDatabaseChange).toHaveBeenCalledWith('$database');
  });

  it('shows every failed lookup, not only the first', async () => {
    const ds = fakeDatasource({});
    ds.getTableDatabases.mockRejectedValue(new Error('cannot list databases'));
    ds.getTableTables.mockRejectedValue(new Error('cannot list tables'));
    renderPicker(ds, { database: 'db1' });

    expect(await screen.findByText(/cannot list databases; cannot list tables/)).toBeTruthy();
  });

  it.each(['Tab', 'Enter'])('keeps the database on %s after the refresh-on-open settles', async (key) => {
    const ds = fakeDatasource({});
    // Like a real lookup, every response is a new array.
    ds.getTableDatabases.mockImplementation(() => Promise.resolve(['information_schema', 'db1', 'db2']));
    const { props } = renderPicker(ds, { database: 'db2' });
    await waitFor(() => expect(ds.getTableDatabases).toHaveBeenCalledTimes(1));
    await act(async () => {});
    const databaseInput = document.getElementById('iotdb-table-database-A') as HTMLInputElement;

    await act(async () => {
      fireEvent.focus(databaseInput);
      fireEvent.keyDown(databaseInput, { key: 'ArrowDown' });
    });
    await waitFor(() => expect(ds.getTableDatabases).toHaveBeenCalledTimes(2));
    await act(async () => {});
    await act(async () => {
      fireEvent.keyDown(databaseInput, { key });
    });

    expect(props.onDatabaseChange).not.toHaveBeenCalledWith('information_schema');
  });

  it('gives each query its own element ids', () => {
    const ds = fakeDatasource({});
    renderPicker(ds, { refId: 'B' });
    expect(document.getElementById('iotdb-table-database-B')).toBeTruthy();
    expect(document.getElementById('iotdb-table-table-B')).toBeTruthy();
  });
});
