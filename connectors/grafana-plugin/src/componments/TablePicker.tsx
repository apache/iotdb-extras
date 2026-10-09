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
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ScopedVars, SelectableValue } from '@grafana/data';
import { Button, Select } from '@grafana/ui';
import { DataSource } from '../datasource';
import { starterTableQuery } from '../tableQuery';
import { TableColumn } from '../types';
import { QueryInlineField } from './Form';

interface Props {
  datasource: DataSource;
  // Identifies the query, so that two table-model queries in one panel get distinct element ids.
  refId: string;
  // The panel's scoped variables, so that names resolve as they will when the query runs.
  scopedVars?: ScopedVars;
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

// pickerOptions turns the listed names into options and appends the current
// value when it is not among them (a typed or variable name), so that the
// select can match its value to an option and open with that option focused.
function pickerOptions(names: string[] | undefined, current: string): Array<SelectableValue<string>> {
  const options = (names ?? []).map((name) => ({ label: name, value: name }));
  return current && !options.some((o) => o.value === current)
    ? [...options, { label: current, value: current }]
    : options;
}

function errorMessage(err: unknown): string {
  const e = err as { data?: { message?: string }; message?: string };
  return e?.data?.message ?? e?.message ?? String(err);
}

// useLoaded runs one metadata lookup whenever its key changes. State is only
// written when a lookup settles, and a result is only used while its key is
// current. A failed lookup keeps its message; the pickers still accept a typed
// name, so the editor stays usable when metadata cannot be read. With
// keepPrevious, the last value stays available while a new lookup is running.
function useLoaded<T>(key: string, load: (() => Promise<T>) | undefined, keepPrevious = false): Loaded<T> {
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
    return { value: keepPrevious ? result?.value : undefined, loading: true };
  }
  return { value: result.value, error: result.error, loading: false };
}

// useTypedName lets a typed name count when the picker loses focus. A select
// only commits typed text on Enter, Tab or a click on the custom-value row, and
// clears it on blur; react-select calls onBlur before clearing, so the text is
// still known there.
function useTypedName(current: string, commit: (name: string) => void) {
  const typed = useRef('');
  return {
    onInputChange: (value: string, meta: { action: string }) => {
      if (meta.action === 'input-change') {
        typed.current = value;
      } else if (meta.action === 'set-value' || meta.action === 'menu-close') {
        typed.current = '';
      }
    },
    onBlur: () => {
      const name = typed.current.trim();
      typed.current = '';
      if (name && name !== current) {
        commit(name);
      }
    },
  };
}

// TablePicker lists the databases, tables and columns of the connected server
// for the table-model editor, and can fill in a starter query for the chosen
// table.
export function TablePicker({
  datasource,
  refId,
  scopedVars,
  database,
  table,
  onDatabaseChange,
  onTableChange,
  onSqlChange,
}: Props) {
  // Lookups are keyed on the resolved names, so a dashboard variable that
  // changes value fetches the new database's tables and columns.
  const resolvedDatabase = database ? datasource.resolveTemplate(database, scopedVars) : '';
  const resolvedTable = table ? datasource.resolveTemplate(table, scopedVars) : '';
  // Opening the database list asks the server again, so a database created
  // later, or a lookup that failed, is picked up without reopening the editor.
  const [databaseRefresh, setDatabaseRefresh] = useState(0);
  const databases = useLoaded(`databases|${databaseRefresh}`, () => datasource.getTableDatabases(), true);
  const tables = useLoaded(
    `tables|${resolvedDatabase}`,
    resolvedDatabase ? () => datasource.getTableTables(resolvedDatabase) : undefined
  );
  const columns = useLoaded<TableColumn[]>(
    `columns|${resolvedDatabase}|${resolvedTable}`,
    resolvedDatabase && resolvedTable ? () => datasource.getTableColumns(resolvedDatabase, resolvedTable) : undefined
  );
  const errors = Array.from(new Set([databases.error, tables.error, columns.error].filter(Boolean)));

  const databaseOptions = useMemo(() => pickerOptions(databases.value, database), [databases.value, database]);
  const tableOptions = useMemo(() => pickerOptions(tables.value, table), [tables.value, table]);
  const commitDatabase = (name: string) => onDatabaseChange(name.trim());
  const commitTable = (name: string) => onTableChange(name.trim());
  const typedDatabase = useTypedName(database, commitDatabase);
  const typedTable = useTypedName(table, commitTable);

  return (
    <>
      <div className="gf-form">
        <QueryInlineField label={'DATABASE'}>
          {/* Select rather than Combobox: with Combobox, picking information_schema
              crashed Explore on Grafana 12.3 with React error 185 raised from the
              popover's floating-ui reference; Select does not. */}
          <Select
            inputId={`iotdb-table-database-${refId}`}
            width={30}
            options={databaseOptions}
            value={databaseOptions.find((o) => o.value === database) ?? null}
            isLoading={databases.loading}
            allowCustomValue
            allowCreateWhileLoading
            placeholder={'database name (required)'}
            onChange={(v) => commitDatabase(v?.value ?? '')}
            onInputChange={typedDatabase.onInputChange}
            onBlur={typedDatabase.onBlur}
            onOpenMenu={() => setDatabaseRefresh((n) => n + 1)}
          />
        </QueryInlineField>
        <QueryInlineField label={'TABLE'}>
          <Select
            inputId={`iotdb-table-table-${refId}`}
            width={30}
            options={tableOptions}
            value={tableOptions.find((o) => o.value === table) ?? null}
            isLoading={tables.loading}
            disabled={!database}
            allowCustomValue
            allowCreateWhileLoading
            isClearable
            placeholder={'pick a table to list its columns'}
            onChange={(v) => commitTable(v?.value ?? '')}
            onInputChange={typedTable.onInputChange}
            onBlur={typedTable.onBlur}
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
      {errors.length > 0 && (
        <div className="gf-form">
          <QueryInlineField label={''}>
            <div className="gf-form-label">Could not read metadata: {errors.join('; ')}</div>
          </QueryInlineField>
        </div>
      )}
    </>
  );
}
