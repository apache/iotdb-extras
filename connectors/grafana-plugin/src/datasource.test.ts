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
import { DataSource, tableMetadataTtlMs } from './datasource';
import { applyQueryType, getQueryType, IoTDBQuery } from './types';
import { ScopedVars } from '@grafana/data';

const mockReplace = jest.fn();
const mockContainsTemplate = jest.fn();
const mockGetVariables = jest.fn();

jest.mock('@grafana/runtime', () => ({
  DataSourceWithBackend: class {},
  getTemplateSrv: () => ({
    replace: mockReplace,
    containsTemplate: mockContainsTemplate,
    getVariables: mockGetVariables,
  }),
}));

describe('DataSource', () => {
  let ds: DataSource;

  beforeEach(() => {
    ds = new DataSource({ jsonData: { url: 'http://localhost:6667', username: 'root' } } as any);
    mockReplace.mockReset();
    mockContainsTemplate.mockReset();
    mockGetVariables.mockReset();
    mockGetVariables.mockReturnValue([]);
  });

  describe('applyTemplateVariables - prefixPath expansion', () => {
    const baseQuery: Partial<IoTDBQuery> = {
      sqlType: 'SQL: Full Customized',
      expression: [],
      prefixPath: [],
      condition: '',
      control: '',
    };
    const scopedVars: ScopedVars = {};

    it('should pass through literal paths without variables', () => {
      mockContainsTemplate.mockReturnValue(false);
      const query = { ...baseQuery, prefixPath: ['root.app.device1', 'root.app.device2'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual(['root.app.device1', 'root.app.device2']);
      expect(mockReplace).not.toHaveBeenCalled();
    });

    it('should handle single-value variable without expansion', () => {
      mockContainsTemplate.mockReturnValue(true);
      mockGetVariables.mockReturnValue([
        { name: 'device', current: { value: 'device1' }, options: [{ value: '$__all' }, { value: 'device1' }] },
      ]);
      mockReplace.mockReturnValue('device1');
      const query = { ...baseQuery, prefixPath: ['root.app.${device}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual(['root.app.device1']);
    });

    it('should expand multi-value variable into multiple paths', () => {
      mockContainsTemplate.mockReturnValue(true);
      mockGetVariables.mockReturnValue([
        {
          name: 'device',
          current: { value: ['device1', 'device2', 'device3'] },
          options: [{ value: '$__all' }, { value: 'device1' }, { value: 'device2' }, { value: 'device3' }],
        },
      ]);
      const query = { ...baseQuery, prefixPath: ['root.app.${device}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual(['root.app.device1', 'root.app.device2', 'root.app.device3']);
    });

    it('should handle mixed literal and template paths', () => {
      mockContainsTemplate.mockImplementation((path: string) => path.includes('${'));
      mockGetVariables.mockReturnValue([
        { name: 'device', current: { value: ['device1', 'device2'] }, options: [{ value: '$__all' }, { value: 'device1' }, { value: 'device2' }] },
      ]);
      const query = {
        ...baseQuery,
        prefixPath: ['root.static.path', 'root.app.${device}'],
      } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual(['root.static.path', 'root.app.device1', 'root.app.device2']);
    });

    it('should handle multiple template paths each with multi-value variables', () => {
      mockContainsTemplate.mockReturnValue(true);
      mockGetVariables.mockReturnValue([
        { name: 'var1', current: { value: ['d1', 'd2'] }, options: [{ value: '$__all' }, { value: 'd1' }, { value: 'd2' }] },
        { name: 'var2', current: { value: ['d3', 'd4'] }, options: [{ value: '$__all' }, { value: 'd3' }, { value: 'd4' }] },
      ]);
      const query = {
        ...baseQuery,
        prefixPath: ['root.a.${var1}', 'root.b.${var2}'],
      } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual(['root.a.d1', 'root.a.d2', 'root.b.d3', 'root.b.d4']);
    });

    it('should still replace expression fields normally', () => {
      mockContainsTemplate.mockReturnValue(false);
      mockReplace.mockImplementation((v: string) => v.replace('${metric}', 'temperature'));
      const query = {
        ...baseQuery,
        prefixPath: ['root.app.device1'],
        expression: ['${metric}'],
      } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.expression).toEqual(['temperature']);
    });

    it('should expand $__all using options list', () => {
      mockContainsTemplate.mockReturnValue(true);
      mockGetVariables.mockReturnValue([
        {
          name: 'target',
          current: { value: '$__all' },
          options: [{ value: '$__all' }, { value: 'apache_iotdb' }, { value: 'timecho' }, { value: 'influxdb' }],
        },
      ]);
      const query = { ...baseQuery, prefixPath: ['root.market_ops.pypi.${target}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual([
        'root.market_ops.pypi.apache_iotdb',
        'root.market_ops.pypi.timecho',
        'root.market_ops.pypi.influxdb',
      ]);
    });

    it('should use scopedVars when variable is present there', () => {
      mockContainsTemplate.mockReturnValue(true);
      const scoped: ScopedVars = { device: { text: 'Device 1', value: 'device1' } };
      const query = { ...baseQuery, prefixPath: ['root.app.${device}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scoped);

      expect(result.prefixPath).toEqual(['root.app.device1']);
    });

    it('should use scopedVars array value when variable is present there', () => {
      mockContainsTemplate.mockReturnValue(true);
      const scoped: ScopedVars = { device: { text: 'All', value: ['dev1', 'dev2'] } } as any;
      const query = { ...baseQuery, prefixPath: ['root.app.${device}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scoped);

      expect(result.prefixPath).toEqual(['root.app.dev1', 'root.app.dev2']);
    });

    it('should expand $__all from scopedVars using options list', () => {
      mockContainsTemplate.mockReturnValue(true);
      mockGetVariables.mockReturnValue([
        {
          name: 'device',
          current: { value: '$__all' },
          options: [{ value: '$__all' }, { value: 'device1' }, { value: 'device2' }, { value: 'device3' }],
        },
      ]);
      const scoped: ScopedVars = { device: { text: 'All', value: '$__all' } } as any;
      const query = { ...baseQuery, prefixPath: ['root.app.${device}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scoped);

      expect(result.prefixPath).toEqual(['root.app.device1', 'root.app.device2', 'root.app.device3']);
    });

    it('should fallback to replace whole path when variable cannot be resolved', () => {
      mockContainsTemplate.mockReturnValue(true);
      mockGetVariables.mockReturnValue([]);
      mockReplace.mockReturnValue('root.app.unknown');
      const query = { ...baseQuery, prefixPath: ['root.app.${missing}'] } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.prefixPath).toEqual(['root.app.unknown']);
      expect(mockReplace).toHaveBeenCalledWith('root.app.${missing}', scopedVars);
    });

    it('should still replace condition and control fields', () => {
      mockContainsTemplate.mockReturnValue(false);
      mockReplace.mockImplementation((v: string) => v.replace('${threshold}', '100'));
      const query = {
        ...baseQuery,
        prefixPath: ['root.app.device1'],
        condition: 'value > ${threshold}',
        control: 'limit ${threshold}',
      } as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, scopedVars);

      expect(result.condition).toBe('value > 100');
      expect(result.control).toBe('limit 100');
    });
  });

  describe('applyTemplateVariables - SQL: Drop-down List', () => {
    it('should replace groupBy and fillClauses fields', () => {
      mockReplace.mockImplementation((v: string) => v.replace('${interval}', '1h'));
      const query = {
        sqlType: 'SQL: Drop-down List',
        groupBy: { samplingInterval: '${interval}', step: '${interval}', groupByLevel: '1' },
        fillClauses: 'previous',
      } as unknown as IoTDBQuery;

      const result = ds.applyTemplateVariables(query, {});

      expect(result.groupBy?.samplingInterval).toBe('1h');
      expect(result.groupBy?.step).toBe('1h');
    });
  });

  describe('metricFindQuery', () => {
    it('extracts and expands a standard variable query object', async () => {
      const scopedVars: ScopedVars = { cluster: { text: 'cluster-a', value: 'cluster-a' } };
      const sql =
        "table:metrics_validation:SELECT DISTINCT instance FROM sys_cpu_cores WHERE cluster IN (${cluster:sqlstring})";
      const expandedSql =
        "table:metrics_validation:SELECT DISTINCT instance FROM sys_cpu_cores WHERE cluster IN ('cluster-a')";
      const getVariablesResult = jest.spyOn(ds, 'getVariablesResult').mockResolvedValue([]);
      mockReplace.mockReturnValue(expandedSql);

      await ds.metricFindQuery({ query: sql, refId: 'StandardVariableQuery' }, { scopedVars });

      expect(mockReplace).toHaveBeenCalledWith(sql, scopedVars);
      expect(getVariablesResult).toHaveBeenCalledWith(expandedSql);
    });

    it('keeps legacy string variable queries compatible', async () => {
      const sql = 'show child paths root.sg';
      const getVariablesResult = jest.spyOn(ds, 'getVariablesResult').mockResolvedValue([]);
      mockReplace.mockReturnValue(sql);

      await ds.metricFindQuery(sql);

      expect(mockReplace).toHaveBeenCalledWith(sql, undefined);
      expect(getVariablesResult).toHaveBeenCalledWith(sql);
    });

    it('rejects variable query objects without a string query field', async () => {
      const getVariablesResult = jest.spyOn(ds, 'getVariablesResult').mockResolvedValue([]);

      await expect(ds.metricFindQuery({ refId: 'StandardVariableQuery' })).rejects.toThrow(
        'Variable query must be a string or an object with a string query field'
      );

      expect(mockReplace).not.toHaveBeenCalled();
      expect(getVariablesResult).not.toHaveBeenCalled();
    });
  });
});

describe('query type model', () => {
  const query = { sqlType: 'SQL: Table Model' } as IoTDBQuery;

  it('keeps omitted query flags backward-compatible as Range', () => {
    expect(getQueryType(query)).toBe('Range');
  });

  it.each([
    ['Range', false, true],
    ['Instant', true, false],
    ['Both', true, true],
  ] as const)('serializes %s into dashboard query flags', (queryType, instant, range) => {
    const result = applyQueryType(query, queryType);

    expect(result.instant).toBe(instant);
    expect(result.range).toBe(range);
    expect(getQueryType(result)).toBe(queryType);
  });

  it('recognizes an existing dashboard instant target without a range field', () => {
    expect(getQueryType({ ...query, instant: true })).toBe('Instant');
  });
});

describe('table-model metadata lookups', () => {
  let now: number;
  let clock: jest.SpyInstance<number, []>;

  beforeEach(() => {
    now = 1_000_000;
    clock = jest.spyOn(Date, 'now').mockImplementation(() => now);
  });

  afterEach(() => {
    clock.mockRestore();
  });

  function metadataDataSource() {
    const ds = new DataSource({ jsonData: { url: 'http://localhost:6667', username: 'root' } } as any);
    const getResource = jest.fn().mockResolvedValue([]);
    (ds as unknown as { getResource: jest.Mock }).getResource = getResource;
    return { ds, getResource };
  }

  it('do not raise a global error alert, since the editor shows failures inline', async () => {
    const { ds, getResource } = metadataDataSource();
    await ds.getTableDatabases();
    await ds.getTableTables('db');
    await ds.getTableColumns('db', 't');
    expect(getResource.mock.calls).toEqual([
      ['tableDatabases', undefined, { showErrorAlert: false }],
      ['tableTables', { database: 'db' }, { showErrorAlert: false }],
      ['tableColumns', { database: 'db', table: 't' }, { showErrorAlert: false }],
    ]);
  });

  it('reuse a recent result for the same database and table', async () => {
    const { ds, getResource } = metadataDataSource();
    getResource.mockImplementation((path: string, params?: Record<string, string>) =>
      Promise.resolve([`${path} ${JSON.stringify(params ?? {})}`])
    );

    expect(await ds.getTableTables('db1')).toEqual(['tableTables {"database":"db1"}']);
    expect(await ds.getTableTables('db1')).toEqual(['tableTables {"database":"db1"}']);
    await ds.getTableTables('db2');
    await ds.getTableColumns('db1', 't');
    await ds.getTableColumns('db1', 't');
    await ds.getTableColumns('db1', 'u');
    await ds.getTableColumns('db2', 't');
    await ds.getTableDatabases();
    await ds.getTableDatabases();

    expect(getResource.mock.calls.map(([path, params]) => [path, params])).toEqual([
      ['tableTables', { database: 'db1' }],
      ['tableTables', { database: 'db2' }],
      ['tableColumns', { database: 'db1', table: 't' }],
      ['tableColumns', { database: 'db1', table: 'u' }],
      ['tableColumns', { database: 'db2', table: 't' }],
      ['tableDatabases', undefined],
    ]);
  });

  it('ask the server again once the cached result has expired', async () => {
    const { ds, getResource } = metadataDataSource();
    getResource.mockResolvedValueOnce(['cpu']).mockResolvedValueOnce(['cpu', 'mem']);

    await ds.getTableTables('db1');
    now += tableMetadataTtlMs - 1;
    expect(await ds.getTableTables('db1')).toEqual(['cpu']);
    expect(getResource).toHaveBeenCalledTimes(1);

    now += 1;
    expect(await ds.getTableTables('db1')).toEqual(['cpu', 'mem']);
    expect(getResource).toHaveBeenCalledTimes(2);
  });

  it('do not keep a failed lookup', async () => {
    const { ds, getResource } = metadataDataSource();
    getResource.mockRejectedValueOnce(new Error('Unknown database db1')).mockResolvedValueOnce(['cpu']);

    await expect(ds.getTableTables('db1')).rejects.toThrow('Unknown database db1');
    expect(await ds.getTableTables('db1')).toEqual(['cpu']);
    expect(getResource).toHaveBeenCalledTimes(2);
  });

  it('ask the server past the cache on refresh, and keep the fresh answer', async () => {
    const { ds, getResource } = metadataDataSource();
    getResource.mockResolvedValueOnce(['db1']).mockResolvedValueOnce(['db1', 'db2']);

    await ds.getTableDatabases();
    expect(await ds.getTableDatabases({ refresh: true })).toEqual(['db1', 'db2']);
    expect(await ds.getTableDatabases()).toEqual(['db1', 'db2']);
    expect(getResource).toHaveBeenCalledTimes(2);
  });

  it("pass the caller's abort signal to the request", async () => {
    const { ds, getResource } = metadataDataSource();
    const controller = new AbortController();

    await ds.getTableColumns('db1', 't', { signal: controller.signal });

    expect(getResource).toHaveBeenCalledWith(
      'tableColumns',
      { database: 'db1', table: 't' },
      { showErrorAlert: false, abortSignal: controller.signal }
    );
    // Equality alone would accept any AbortSignal; it must be the caller's.
    expect(getResource.mock.calls[0][2].abortSignal).toBe(controller.signal);
  });
});
