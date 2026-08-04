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

/*
 * Compatibility smoke test for the minimum Grafana version this plugin declares.
 *
 * plugin.json pins `grafanaDependency`, but the @grafana/* packages are webpack
 * externals: they are resolved from the host Grafana at runtime rather than
 * bundled. A plugin built against a newer Grafana therefore compiles cleanly and
 * then fails inside an older host, with no bundled fallback. A declared floor is
 * only meaningful if something actually boots that floor and loads the plugin.
 *
 * CI runs this against the exact version parsed out of `grafanaDependency`, so
 * raising or lowering that value changes what is tested without touching this file.
 *
 * The two checks are deliberately different in kind:
 *   1. the frontend module resolves its externals against this Grafana, and
 *   2. the backend answers a table-model query end to end.
 * A version mismatch shows up in the first; a wiring or protocol break in the second.
 */

const DATASOURCE_NAME = 'IoTDB';
const TABLE_MODEL_SQL_TYPE = 'SQL: Table Model';

test.describe('minimum declared Grafana version', () => {
  test('the plugin frontend loads and its config editor renders', async ({ page, selectors }) => {
    // Reaching the settings page at all requires Grafana to have loaded module.js
    // and every @grafana/* external it imports. On a host older than the declared
    // floor this is where the failure surfaces -- typically a blank panel plus an
    // unresolved-import error on the console, not a build error.
    const consoleErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });

    await page.goto('/connections/datasources');
    await page.getByRole('link', { name: DATASOURCE_NAME }).click();

    // Fields owned by this plugin's own ConfigEditor, not by Grafana's chrome --
    // so this asserts our module rendered, not merely that the page exists.
    // Located by placeholder rather than by label: @grafana/ui's InlineField
    // renders the caption without a `for` association, so getByLabel does not
    // resolve it.
    await expect(page.getByPlaceholder('iotdb-host:6667 (optional)')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByPlaceholder('please input URL')).toBeVisible();

    // The visibility assertions above are the primary signal: if an @grafana/*
    // external failed to resolve, module.js would not have executed and these
    // fields would not exist. This second check catches the narrower case where
    // the module loads but a named export it expects is missing from the host.
    // Scoped to failures naming this plugin or naming an export/module problem --
    // a bare "Failed to load resource" is Grafana reaching for telemetry and the
    // plugin catalogue, which a sandboxed CI runner refuses and which says nothing
    // about compatibility.
    const moduleFailures = consoleErrors.filter(
      (e) =>
        /apache-iotdb-datasource/i.test(e) ||
        /does not provide an export named|dynamically imported module|SyntaxError|is not exported/i.test(e)
    );
    expect(moduleFailures, `plugin module errors:\n${moduleFailures.join('\n')}`).toHaveLength(0);
  });

  test('a table-model query returns data through the native client', async ({ page }) => {
    // information_schema is present on every IoTDB instance, so this needs no
    // seeding and still exercises the whole path: Grafana -> plugin backend ->
    // native Thrift session -> table-model SQL -> data frame.
    const datasource = await page.evaluate(async (name) => {
      const res = await fetch(`/api/datasources/name/${encodeURIComponent(name)}`);
      if (!res.ok) {
        throw new Error(`datasource lookup failed: ${res.status}`);
      }
      return res.json();
    }, DATASOURCE_NAME);

    expect(datasource.type, 'the provisioned datasource should be this plugin').toBe('apache-iotdb-datasource');

    const now = Date.now();
    const body = {
      from: String(now - 3_600_000),
      to: String(now),
      queries: [
        {
          refId: 'A',
          datasource: { uid: datasource.uid, type: datasource.type },
          sqlType: TABLE_MODEL_SQL_TYPE,
          database: 'information_schema',
          sql: 'SELECT table_name FROM tables LIMIT 5',
          format: 'Table',
          // The tree-model fields are part of the shared query model and are sent
          // by the editor even in table mode; keep them so the payload matches
          // what a real panel produces.
          expression: [],
          prefixPath: [],
          paths: [],
          options: [],
          condition: '',
          control: '',
          fillClauses: '',
          isDropDownList: false,
          hide: false,
          startTime: now - 3_600_000,
          endTime: now,
        },
      ],
    };

    const result = await page.evaluate(async (payload) => {
      const res = await fetch('/api/ds/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      return { status: res.status, json: await res.json() };
    }, body);

    expect(result.status, `/api/ds/query returned ${result.status}`).toBe(200);

    const frameA = result.json?.results?.A;
    expect(frameA?.error, `backend error: ${frameA?.error ?? ''}`).toBeFalsy();

    const frames = frameA?.frames ?? [];
    expect(frames.length, 'the table-model query should return at least one frame').toBeGreaterThan(0);

    const rowCount = frames[0]?.data?.values?.[0]?.length ?? 0;
    expect(rowCount, 'information_schema.tables should yield at least one row').toBeGreaterThan(0);
  });
});
