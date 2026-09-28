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

import { expect, test } from '@grafana/plugin-e2e';

const DATASOURCE_NAME = 'IoTDB';

// information_schema exists on every IoTDB instance, so the pickers can be
// exercised against the real server without seeding any data.
test('the table-model editor lists databases, tables and columns from the server', async ({ page }) => {
  await page.goto('/explore');

  await page.getByText('SQL: Full Customized', { exact: true }).first().click();
  await page.getByText('SQL: Table Model', { exact: true }).last().click();

  await page.locator('#iotdb-table-database').click();
  await page.getByRole('option', { name: 'information_schema' }).click();

  await page.locator('#iotdb-table-table').click();
  await page.getByRole('option', { name: 'tables', exact: true }).click();

  // The COLUMNS row is built from DESC, grouped by category.
  await expect(page.getByText('table_name', { exact: false })).toBeVisible({ timeout: 30_000 });

  await page.getByRole('button', { name: 'Use starter query' }).click();
  const sql = await page.locator('textarea').first().inputValue();
  // information_schema.tables has no time column, so no time filter is added.
  expect(sql).toBe('SELECT "database", "table_name" FROM "tables"');

  // The generated query has to run, not just look right.
  const datasource = await page.evaluate(async (name) => {
    const res = await fetch(`/api/datasources/name/${encodeURIComponent(name)}`);
    return res.json();
  }, DATASOURCE_NAME);
  const now = Date.now();
  const result = await page.evaluate(
    async (payload) => {
      const res = await fetch('/api/ds/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      return { status: res.status, json: await res.json() };
    },
    {
      from: String(now - 3_600_000),
      to: String(now),
      queries: [
        {
          refId: 'A',
          datasource: { uid: datasource.uid, type: datasource.type },
          sqlType: 'SQL: Table Model',
          database: 'information_schema',
          sql,
          format: 'Table',
          expression: [],
          prefixPath: [],
          paths: [],
          options: [],
          condition: '',
          control: '',
          fillClauses: '',
          isDropDownList: false,
          hide: false,
        },
      ],
    }
  );
  expect(result.status).toBe(200);
  const frame = result.json.results.A;
  expect(frame.error, `query error: ${frame.error}`).toBeUndefined();
  expect(frame.frames[0].data.values[0].length).toBeGreaterThan(0);
});
