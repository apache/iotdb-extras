<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at
    
        http://www.apache.org/licenses/LICENSE-2.0
    
    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

## Grafana-Plugin


Grafana is an open source volume metrics monitoring and visualization tool, which can be used to present time series data and analyze application runtime status.

We developed the Grafana-Plugin for IoTDB, using the IoTDB REST service to present time series data and providing many visualization methods for time series data.

IoTDB grafana plugin supports grafana version 9.3.0 and above

### How to use Grafana-Plugin

#### Access Grafana dashboard

Grafana displays data in a web page dashboard. Please open your browser and visit `http://<ip>:<port>` when using it.

* IP is the IP of the server where your Grafana is located, and Port is the running port of Grafana (default 3000).

* The default login username and password are both `admin`.


#### Add IoTDB as Data Source

Click the `Settings` icon on the left, select the `Data Source` option, and then click `Add data source`.

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/datasource_1.png?raw=true">

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/datasource_2.png?raw=true">

Select the `Apache IoTDB` data source.

* Fill in `http://<ip>:<port>` in the `URL` field
   * ip is the host ip where your IoTDB server is located
   * port is the running port of the REST service (default 18080).
* Enter the username and password of the IoTDB server

Click `Save & Test`, and `Success` will appear.

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/datasource_3.png?raw=true">


#### Create a new Panel

Click the `Dashboards` icon on the left, and select `Manage` option.

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/manage.png?raw=true">

Click the `New Dashboard` icon on the top right, and select `Add an empty panel` option.

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/add%20empty%20panel.png?raw=true">

Grafana plugin supports SQL: Full Customized mode and SQL: Drop-down List mode for the tree model, and SQL: Table Model mode for the table model. The default mode is SQL: Full Customized mode.

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/grafana_input_style.png?raw=true">

##### SQL: Full Customized input method

Enter content in the SELECT, FROM , WHERE and CONTROL input box, where the WHERE and CONTROL input boxes are optional.

If a query involves multiple expressions, we can click `+` on the right side of the SELECT input box to add expressions in the SELECT clause, or click `+` on the right side of the FROM input box to add a path prefix:

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/grafana_input.png?raw=true">

SELECT input box: contents can be the time series suffix, function, udf, arithmetic expression, or nested expressions. You can also use the as clause to rename the result.

Here are some examples of valid SELECT content:

* `s1`
* `top_k(s1, 'k'='1') as top`
* `sin(s1) + cos(s1 + s2)`
* `udf(s1) as "alias"`

FROM input box: contents must be the prefix path of the time series, such as `root.sg.d`.

WHERE input box: contents should be the filter condition of the query, such as `time > 0` or `s1 < 1024 and s2 > 1024`.

CONTROL input box: contents should be a special clause that controls the query type and output format.
The GROUP BY input box supports the use of grafana's global variables to obtain the current time interval changes $__from (start time), $__to (end time)

Here are some examples of valid CONTROL content:

*  `GROUP BY ([$__from, $__to), 1d)`
*  `GROUP BY ([$__from, $__to),3h,1d)`
*  `GROUP BY ([2017-11-01T00:00:00, 2017-11-07T23:00:00), 1d)`
*  `GROUP BY ([2017-11-01 00:00:00, 2017-11-07 23:00:00), 3h, 1d)`
*  `GROUP BY ([$__from, $__to), 1m) FILL (PREVIOUSUNTILLAST)`
*  `GROUP BY ([2017-11-07T23:50:00, 2017-11-07T23:59:00), 1m) FILL (PREVIOUSUNTILLAST)`
*  `GROUP BY ([2017-11-07T23:50:00, 2017-11-07T23:59:00), 1m) FILL (PREVIOUS, 1m)`
*  `GROUP BY ([2017-11-07T23:50:00, 2017-11-07T23:59:00), 1m) FILL (LINEAR, 5m, 5m)`
*  `GROUP BY ((2017-11-01T00:00:00, 2017-11-07T23:00:00], 1d), LEVEL=1`
*  `GROUP BY ([0, 20), 2ms, 3ms), LEVEL=1`


Tip: Statements like `select * from root.xx.**` are not recommended because those statements may cause OOM.

##### SQL: Drop-down List

Select a time series in the TIME-SERIES selection box, select a function in the FUNCTION option, and enter the contents in the SAMPLING INTERVAL、SLIDING STEP、LEVEL、FILL input boxes, where TIME-SERIES is a required item and the rest are non required items.

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/grafana_input2.png?raw=true">

##### SQL: Table Model

Queries IoTDB's table model (IoTDB 2.x) through the native Go client ([apache/iotdb-client-go](https://github.com/apache/iotdb-client-go)). Enter a standard SQL statement in the SQL input box, for example:

```sql
SELECT time, device_id, temperature FROM table1 WHERE $__timeFilter(time)
```

Unlike the tree-model modes, which go through the REST service, this mode connects to the IoTDB RPC port (6667 by default). The data source's `rpc address` option sets that endpoint explicitly; when it is left empty, the URL's host with port 6667 is used.

DATABASE input box: the database to run the statement against. Required — every query first runs `USE <database>` on its pooled session, so results stay deterministic regardless of which session the pool hands out. Fully-qualified `database.table` references are still allowed and take precedence over the session database.

SQL input box: a single table-model SELECT statement. The following macros are expanded by the plugin before the statement is sent:

| Macro | Expands to |
| ----- | ---------- |
| `$__timeFilter(col)` | `(col >= <panel start> AND col <= <panel end>)`; `col` defaults to `time` when omitted |
| `$__timeFrom`, `$__timeFrom()` | the panel range start as an ISO 8601 UTC timestamp literal |
| `$__timeTo`, `$__timeTo()` | the panel range end as an ISO 8601 UTC timestamp literal |

The macros expand to ISO 8601 timestamp literals (e.g. `2020-09-13T12:26:40.000+00:00`), which the server interprets in its own configured `timestamp_precision` — so time filtering works unchanged on `ms`, `us` and `ns` servers, and TIMESTAMP values are likewise converted by the client using the server-reported precision.

FORMAT option:

* `Time series` (default): rows are sorted by the first TIMESTAMP column, and a result that contains tag/string columns next to numeric columns is pivoted into one series per tag combination — so a query returning several devices renders as separate lines in a time-series panel. Note that the pivot requires timestamps without nulls; results are sorted by the plugin, so an `ORDER BY` clause is not needed.
* `Table`: rows are returned exactly as the server sent them (use this with the table panel, or when your `ORDER BY` should be preserved).

Result columns are typed from the result set's data types: TIMESTAMP columns become Grafana time fields, INT32/INT64 become integers, FLOAT/DOUBLE become floats, BOOLEAN becomes booleans, and TEXT/STRING/DATE/BLOB render as strings.

There is no server-imposed row cap on this path; add a `LIMIT` clause when querying wide time ranges over dense data to bound the result size.

#### Support for variables and template functions

Both SQL: Full Customized and SQL: Drop-down List input methods support the variable and template functions of grafana. In the following example, raw input method is used, and aggregation is similar.

##### Multi-value variable expansion in FROM (prefixPath)

When a multi-value template variable is used in the FROM input box (prefixPath), the plugin automatically expands it into multiple paths. This enables the common "global variable filter" pattern where a single dashboard dropdown controls all panels.

For example, define a multi-select variable `device` with values `device1`, `device2`, ..., `device8`. Then in the FROM input box, enter:

```
root.application.${device}
```

When the user selects `device1` and `device2`, the plugin internally expands this to:

```
root.application.device1
root.application.device2
```

Only the selected devices are queried from IoTDB — no client-side filtering or transformations needed.

This works with any number of prefixPath entries. Literal paths (without variables) and single-value variables behave the same as before.

After creating a new Panel, click the Settings button in the upper right corner:

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/setconf.png?raw=true">

Select `Variables`, click `Add variable`:

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/addvaribles.png?raw=true">

Example 1：Enter `Name`, `Label`, and `Query`, and then click the `Update` button:

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/variblesinput.png?raw=true">

Apply Variables, enter the variable in the `grafana panel` and click the `save` button:

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/applyvariables.png?raw=true">

Example 2: Nested use of variables:

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/variblesinput2.png?raw=true">

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/variblesinput2-1.png?raw=true">

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/variblesinput2-2.png?raw=true">


Example 3: using function variables

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/variablesinput3.png?raw=true">

<img style="width:100%; max-width:800px; max-height:600px; margin-left:auto; margin-right:auto; display:block;" src="https://github.com/apache/iotdb-bin-resources/blob/main/docs/UserGuide/Ecosystem%20Integration/Grafana-plugin/variablesinput3-1.png?raw=true">

The Name in the above figure is the variable name and the variable name we will use in the panel in the future. Label is the display name of the variable. If it is empty, the variable of Name will be displayed. Otherwise, the name of the Label will be displayed.
There are Query, Custom, Text box, Constant, DataSource, Interval, Ad hoc filters, etc. in the Type drop-down, all of which can be used in IoTDB's Grafana Plugin
For a more detailed introduction to usage, please check the official manual (https://grafana.com/docs/grafana/latest/variables/)

In addition to the examples above, the following statements are supported:

*  `show databases`
*  `show timeseries`
*  `show child nodes`
*  `show all ttl`
*  `show latest timeseries`
*  `show devices`
*  `select xx from root.xxx limit xx 等sql 查询`

Tip: If the query field contains Boolean data, the result value will be converted to 1 by true and 0 by false.

#### Grafana alert function

This plugin supports Grafana alert function.

For more details about Grafana operation, please refer to the official Grafana documentation:https://grafana.com/docs/grafana/latest/alerting/
