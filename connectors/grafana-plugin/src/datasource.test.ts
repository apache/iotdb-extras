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
import { DataSource } from './datasource';
import { IoTDBQuery } from './types';
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
});
