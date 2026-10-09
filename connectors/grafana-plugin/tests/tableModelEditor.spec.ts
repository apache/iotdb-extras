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

  await page.locator('#iotdb-table-database-A').click();
  await page.getByRole('option', { name: 'information_schema' }).click();

  await page.locator('#iotdb-table-table-A').click();
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

test('the table-model pickers keep their value on Tab, commit typed names on blur and report failures inline', async ({
  page,
}) => {
  await page.goto('/explore');
  await page.getByText('SQL: Full Customized', { exact: true }).first().click();
  await page.getByText('SQL: Table Model', { exact: true }).last().click();

  const table = page.locator('#iotdb-table-table-A');
  await page.locator('#iotdb-table-database-A').click();
  await page.getByRole('option', { name: 'information_schema' }).click();
  await table.click();
  await page.getByRole('option', { name: 'tables', exact: true }).click();
  await expect(page.getByText('table_name', { exact: false })).toBeVisible({ timeout: 30_000 });

  // Opening a picker and pressing Tab must keep its value. "tables" is not the
  // first table of information_schema, so a picker that focused the first
  // option on open would switch to "columns" here.
  await table.click();
  await expect(page.getByRole('option', { name: 'tables', exact: true })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByText('table_name', { exact: false })).toBeVisible();
  await expect(page.getByText('column_name', { exact: false })).toHaveCount(0);

  // A table name typed and left by clicking elsewhere is kept.
  await table.click();
  await page.keyboard.type('columns');
  // Leave the picker the way a click elsewhere does (the open menu covers the labels).
  await table.blur();
  await expect(page.getByText('column_name', { exact: false })).toBeVisible({ timeout: 30_000 });

  // Running the query from the editor sends the picked database and table.
  const request = page.waitForRequest(
    (r) => r.url().includes('/api/ds/query') && r.method() === 'POST' && (r.postData() ?? '').includes('Table Model')
  );
  await page.getByRole('button', { name: 'Use starter query' }).click();
  await page.getByRole('button', { name: /run query/i }).first().click();
  const sent = (await request).postDataJSON().queries[0];
  expect(sent.database).toBe('information_schema');
  expect(sent.table).toBe('columns');

  // A failed lookup is reported next to the pickers and raises no global alert.
  await table.click();
  await page.keyboard.type('no_such_table');
  // Leave the picker the way a click elsewhere does (the open menu covers the labels).
  await table.blur();
  await expect(page.getByText('Could not read metadata', { exact: false })).toBeVisible({ timeout: 30_000 });
  // Explore's own default query can leave an unrelated alert when the page
  // opens, so look for an alert about this lookup specifically.
  await page.waitForTimeout(1_000);
  await expect(page.getByTestId('data-testid Alert error').filter({ hasText: 'no_such_table' })).toHaveCount(0);
});

