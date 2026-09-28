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
import React, { useEffect, useState } from 'react';
import { SelectableValue } from '@grafana/data';
import { Button, Select } from '@grafana/ui';
import { DataSource } from '../datasource';
import { starterTableQuery } from '../tableQuery';
import { TableColumn } from '../types';
import { QueryInlineField } from './Form';

interface Props {
  datasource: DataSource;
  database: string;
  table: string;
  onDatabaseChange: (database: string) => void;
  onTableChange: (table: string) => void;
  onSqlChange: (sql: string) => void;
}

interface Loaded<T> {
  value?: T;
  loading: boolean;
  error?: string;
}

const categories = ['TAG', 'FIELD', 'ATTRIBUTE'];

function toOptions(names: string[] | undefined): Array<SelectableValue<string>> {
  return (names ?? []).map((name) => ({ label: name, value: name }));
}

function toValue(name: string): SelectableValue<string> | null {
  return name ? { label: name, value: name } : null;
}

function errorMessage(err: unknown): string {
  const e = err as { data?: { message?: string }; message?: string };
  return e?.data?.message ?? e?.message ?? String(err);
}

// useLoaded runs one metadata lookup whenever its key changes. State is only
// written when a lookup settles, and a result is only used while its key is
// current. A failed lookup keeps its message; the pickers still accept a typed
// name, so the editor stays usable when metadata cannot be read.
function useLoaded<T>(key: string, load: (() => Promise<T>) | undefined): Loaded<T> {
  const [result, setResult] = useState<{ key: string; value?: T; error?: string }>();
  useEffect(() => {
    if (!load) {
      return;
    }
    let cancelled = false;
    load().then(
      (value) => {
        if (!cancelled) {
          setResult({ key, value });
        }
      },
      (err) => {
        if (!cancelled) {
          setResult({ key, error: errorMessage(err) });
        }
      }
    );
    return () => {
      cancelled = true;
    };
    // The key identifies the lookup; `load` is a fresh closure on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  if (!load) {
    return { loading: false };
  }
  if (result?.key !== key) {
    return { loading: true };
  }
  return { value: result.value, error: result.error, loading: false };
}

// TablePicker lists the databases, tables and columns of the connected server
// for the table-model editor, and can fill in a starter query for the chosen
// table.
export function TablePicker({ datasource, database, table, onDatabaseChange, onTableChange, onSqlChange }: Props) {
  const databases = useLoaded('databases', () => datasource.getTableDatabases());
  const tables = useLoaded(`tables|${database}`, database ? () => datasource.getTableTables(database) : undefined);
  const columns = useLoaded<TableColumn[]>(
    `columns|${database}|${table}`,
    database && table ? () => datasource.getTableColumns(database, table) : undefined
  );
  const error = databases.error ?? tables.error ?? columns.error;

  return (
    <>
      <div className="gf-form">
        <QueryInlineField label={'DATABASE'}>
          {/* Select rather than Combobox: with Combobox, picking information_schema
              crashed Explore on Grafana 12.3 with React error 185 raised from the
              popover's floating-ui reference; Select does not. */}
          <Select
            inputId="iotdb-table-database"
            width={30}
            options={toOptions(databases.value)}
            value={toValue(database)}
            isLoading={databases.loading}
            allowCustomValue
            placeholder={'database name (required)'}
            onChange={(v) => onDatabaseChange(v?.value ?? '')}
          />
        </QueryInlineField>
        <QueryInlineField label={'TABLE'}>
          <Select
            inputId="iotdb-table-table"
            width={30}
            options={toOptions(tables.value)}
            value={toValue(table)}
            isLoading={tables.loading}
            disabled={!database}
            allowCustomValue
            isClearable
            placeholder={'pick a table to list its columns'}
            onChange={(v) => onTableChange(v?.value ?? '')}
          />
        </QueryInlineField>
      </div>
      {columns.value && columns.value.length > 0 && (
        <div className="gf-form">
          <QueryInlineField label={'COLUMNS'}>
            <div className="gf-form-label" style={{ flexWrap: 'wrap', height: 'auto', gap: 8 }}>
              {categories
                .map((category) => ({
                  category,
                  names: columns.value!.filter((c) => c.category === category).map((c) => c.name),
                }))
                .filter(({ names }) => names.length > 0)
                .map(({ category, names }) => (
                  <span key={category}>
                    <span className="query-keyword">{category}</span> {names.join(', ')}
                  </span>
                ))}
            </div>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => onSqlChange(starterTableQuery(table, columns.value!))}
              tooltip="Replace the SQL below with a query that selects this table's tag and field columns"
            >
              Use starter query
            </Button>
          </QueryInlineField>
        </div>
      )}
      {error && (
        <div className="gf-form">
          <QueryInlineField label={''}>
            <div className="gf-form-label">Could not read metadata: {error}</div>
          </QueryInlineField>
        </div>
      )}
    </>
  );
}
