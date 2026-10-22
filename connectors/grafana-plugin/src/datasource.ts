/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { DataSourceInstanceSettings, MetricFindValue, ScopedVars } from '@grafana/data';

import { IoTDBOptions, IoTDBQuery, TableColumn } from './types';
import { toMetricFindValue } from './functions';
import { DataSourceWithBackend, getTemplateSrv } from '@grafana/runtime';

// How long a table-model metadata lookup is reused. Short, so that a table
// created meanwhile shows up soon; opening the database list always asks the
// server again (see TableMetadataOptions.refresh).
export const tableMetadataTtlMs = 30000;

export interface TableMetadataOptions {
  // Cancels the request when the caller no longer needs its result.
  signal?: AbortSignal;
  // Asks the server even when a recent result is cached, and caches the answer.
  refresh?: boolean;
}

function variableQueryText(query: unknown): string | undefined {
  if (typeof query === 'string') {
    return query;
  }

  if (query && typeof query === 'object' && 'query' in query) {
    const queryText = (query as { query?: unknown }).query;
    if (typeof queryText === 'string') {
      return queryText;
    }
  }

  return undefined;
}

export class DataSource extends DataSourceWithBackend<IoTDBQuery, IoTDBOptions> {
  username: string;
  url: string;

  constructor(instanceSettings: DataSourceInstanceSettings<IoTDBOptions>) {
    super(instanceSettings);
    this.url = instanceSettings.jsonData.url;
    this.username = instanceSettings.jsonData.username;
  }
  applyTemplateVariables(query: IoTDBQuery, scopedVars: ScopedVars) {
    if (!query.sqlType || query.sqlType === 'SQL: Full Customized') {
      if (query.expression) {
        query.expression.map(
          (_, index) => (query.expression[index] = getTemplateSrv().replace(query.expression[index], scopedVars))
        );
      }
      if (query.prefixPath) {
        const expanded: string[] = [];
        const templateSrv = getTemplateSrv();
        const varPattern = /\$\{(\w+)(?::[^}]*)?\}|\$(\w+)\b/;
        for (const path of query.prefixPath) {
          if (varPattern.test(path)) {
            const varMatch = path.match(/\$\{(\w+)(?::[^}]*)?\}|\$(\w+)\b/);
            if (varMatch) {
              const varName = varMatch[1] || varMatch[2];
              const idx = varMatch.index!;
              const prefix = path.substring(0, idx);
              const suffix = path.substring(idx + varMatch[0].length);
              let values: string[] = [];
              if (scopedVars && scopedVars[varName]) {
                const val = scopedVars[varName].value;
                if (val === '$__all') {
                  const allVars = templateSrv.getVariables() as any[];
                  const found = allVars.find((v: any) => v.name === varName);
                  if (found && found.options) {
                    values = found.options
                      .filter((o: any) => o.value !== '$__all')
                      .map((o: any) => o.value);
                  }
                } else {
                  values = Array.isArray(val) ? val : [String(val)];
                }
              } else {
                const allVars = templateSrv.getVariables() as any[];
                const found = allVars.find((v: any) => v.name === varName);
                if (found) {
                  const current = found.current;
                  if (current) {
                    if (Array.isArray(current.value)) {
                      values = current.value.filter((v: string) => v !== '$__all');
                      if (values.length === 0 && found.options) {
                        values = found.options
                          .filter((o: any) => o.value !== '$__all')
                          .map((o: any) => o.value);
                      }
                    } else if (current.value === '$__all') {
                      if (found.options) {
                        values = found.options
                          .filter((o: any) => o.value !== '$__all')
                          .map((o: any) => o.value);
                      }
                    } else {
                      values = [current.value];
                    }
                  }
                }
                if (values.length === 0) {
                  expanded.push(templateSrv.replace(path, scopedVars));
                  continue;
                }
              }
              const resolvedSuffix = suffix && varPattern.test(suffix)
                ? templateSrv.replace(suffix, scopedVars)
                : suffix;
              for (const val of values) {
                expanded.push(prefix + val + resolvedSuffix);
              }
            } else {
              expanded.push(templateSrv.replace(path, scopedVars));
            }
          } else {
            expanded.push(path);
          }
        }
        query.prefixPath = expanded;
      }
     
      if (query.condition) {
        query.condition = getTemplateSrv().replace(query.condition, scopedVars);
      }
      if (query.control) {
        query.control = getTemplateSrv().replace(query.control, scopedVars);
      }
    } else if (query.sqlType === 'SQL: Drop-down List') {
      if (query.groupBy?.samplingInterval) {
        query.groupBy.samplingInterval = getTemplateSrv().replace(query.groupBy.samplingInterval, scopedVars);
      }
      if (query.groupBy?.step) {
        query.groupBy.step = getTemplateSrv().replace(query.groupBy.step, scopedVars);
      }
      if (query.groupBy?.groupByLevel) {
        query.groupBy.groupByLevel = getTemplateSrv().replace(query.groupBy.groupByLevel, scopedVars);
      }
      if (query.fillClauses) {
        query.fillClauses = getTemplateSrv().replace(query.fillClauses, scopedVars);
      }
    } else if (query.sqlType === 'SQL: Table Model') {
      if (query.sql) {
        query.sql = getTemplateSrv().replace(query.sql, scopedVars);
      }
      if (query.database) {
        query.database = getTemplateSrv().replace(query.database, scopedVars);
      }
    }
    return query;
  }

  metricFindQuery(query: any, options?: any): Promise<MetricFindValue[]> {
    const queryText = variableQueryText(query);
    if (queryText === undefined) {
      return Promise.reject(new Error('Variable query must be a string or an object with a string query field'));
    }

    const expandedQuery = getTemplateSrv().replace(queryText, options?.scopedVars);
    return this.getVariablesResult(expandedQuery);
  }

  nodeQuery(query: any, options?: any): Promise<MetricFindValue[]> {
    return this.getChildPaths(query);
  }

  async getChildPaths(detachedPath: string[]) {
    if (this.url.substr(this.url.length - 1, 1) === '/') {
      this.url = this.url.substr(0, this.url.length - 1);
    }
    return this.postResource('getNodes', { url: this.url, data: detachedPath })
      .then((response) => {
        if (response instanceof Array) {
          return response;
        } else {
          throw 'the result is not array';
        }
      })
      .then((data) => data.map(toMetricFindValue));
  }

  // resolveTemplate expands dashboard variables in a database or table name the
  // same way applyTemplateVariables does when the query runs.
  resolveTemplate(value: string, scopedVars?: ScopedVars): string {
    return getTemplateSrv().replace(value, scopedVars);
  }

  // Metadata behind the table-model editor's pickers. Callers pass names that
  // resolveTemplate has already expanded. The editor shows a failed lookup
  // inline, so it is not also raised as a global error alert.
  getTableDatabases(options?: TableMetadataOptions): Promise<string[]> {
    return this.tableMetadata('tableDatabases', {}, options);
  }

  getTableTables(database: string, options?: TableMetadataOptions): Promise<string[]> {
    return this.tableMetadata('tableTables', { database }, options);
  }

  getTableColumns(database: string, table: string, options?: TableMetadataOptions): Promise<TableColumn[]> {
    return this.tableMetadata('tableColumns', { database, table }, options);
  }

  // A successful lookup is kept for tableMetadataTtlMs, keyed on the endpoint
  // and the names it was asked about, so that opening the editor again or
  // switching between queries does not ask the server for names it has just
  // listed. A failed lookup is not kept, so the next one asks again.
  private tableMetadataCache = new Map<string, { expires: number; value: unknown }>();

  private async tableMetadata<T>(
    path: string,
    names: Record<string, string>,
    { signal, refresh = false }: TableMetadataOptions = {}
  ): Promise<T> {
    const key = JSON.stringify([path, names]);
    const cached = this.tableMetadataCache.get(key);
    if (cached && !refresh && cached.expires > Date.now()) {
      return cached.value as T;
    }
    const params = Object.keys(names).length > 0 ? names : undefined;
    const value = await this.getResource<T>(path, params, { showErrorAlert: false, abortSignal: signal });
    const now = Date.now();
    for (const [staleKey, entry] of this.tableMetadataCache) {
      if (entry.expires <= now) {
        this.tableMetadataCache.delete(staleKey);
      }
    }
    this.tableMetadataCache.set(key, { expires: now + tableMetadataTtlMs, value });
    return value;
  }

  async getVariablesResult(sql: string) {
    if (this.url.substr(this.url.length - 1, 1) === '/') {
      this.url = this.url.substr(0, this.url.length - 1);
    }
    return this.getResource('getVariables', { url: this.url, sql: sql })
      .then((response) => {
        if (response instanceof Array) {
          return response;
        } else {
          throw response.message;
        }
      })
      .then((data) => data.map(toMetricFindValue));
  }
}
