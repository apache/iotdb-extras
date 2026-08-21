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

package plugin

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestParseTableVariableQuery(t *testing.T) {
	database, sql, err := parseTableVariableQuery("table:metrics_validation:SELECT DISTINCT instance FROM sys_cpu_cores")
	if err != nil {
		t.Fatalf("valid query rejected: %v", err)
	}
	if database != "metrics_validation" {
		t.Fatalf("database = %q, want metrics_validation", database)
	}
	if sql != "SELECT DISTINCT instance FROM sys_cpu_cores" {
		t.Fatalf("sql = %q", sql)
	}
}

func TestParseTableVariableQueryTrimsWhitespace(t *testing.T) {
	database, sql, err := parseTableVariableQuery("  table: metrics_validation : SELECT 1 ")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if database != "metrics_validation" || sql != "SELECT 1" {
		t.Fatalf("database = %q, sql = %q", database, sql)
	}
}

func TestParseTableVariableQueryRejectsNonTableQuery(t *testing.T) {
	if _, _, err := parseTableVariableQuery("SELECT 1"); err == nil {
		t.Fatalf("non-table query should not parse as a table query")
	}
}

func TestParseTableVariableQueryRejectsEmptyDatabase(t *testing.T) {
	if _, _, err := parseTableVariableQuery("table::SELECT 1"); err == nil || !strings.Contains(err.Error(), "database") {
		t.Fatalf("empty database error = %v", err)
	}
}

func TestParseTableVariableQueryRejectsEmptySQL(t *testing.T) {
	if _, _, err := parseTableVariableQuery("table:metrics_validation:"); err == nil || !strings.Contains(err.Error(), "SQL") {
		t.Fatalf("empty SQL error = %v", err)
	}
}

func TestParseTableVariableQueryRejectsMissingSeparator(t *testing.T) {
	if _, _, err := parseTableVariableQuery("table:metrics_validation"); err == nil {
		t.Fatalf("missing separator should be an error")
	}
}

func TestTableVariableStringsSingleColumnPreservesOrder(t *testing.T) {
	dataSet := &tableQueryDataSet{
		ColumnNames: []string{"instance"},
		DataTypes:   []string{"STRING"},
		Values: [][]interface{}{
			{"node-a.example.test:9091"},
			{"node-b.example.test:9091"},
			{"node-c.example.test:9091"},
		},
	}
	got, err := tableVariableStrings(dataSet)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := []string{"node-a.example.test:9091", "node-b.example.test:9091", "node-c.example.test:9091"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("values = %#v, want %#v", got, want)
	}
}

func TestTableVariableStringsSkipsNulls(t *testing.T) {
	dataSet := &tableQueryDataSet{
		ColumnNames: []string{"instance"},
		DataTypes:   []string{"STRING"},
		Values: [][]interface{}{
			{"a"},
			{nil},
			{"b"},
		},
	}
	got, err := tableVariableStrings(dataSet)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !reflect.DeepEqual(got, []string{"a", "b"}) {
		t.Fatalf("values = %#v, want [a b]", got)
	}
}

func TestTableVariableStringsCoercesNonStrings(t *testing.T) {
	dataSet := &tableQueryDataSet{
		ColumnNames: []string{"node_num"},
		DataTypes:   []string{"INT64"},
		Values: [][]interface{}{
			{int64(7)},
		},
	}
	got, err := tableVariableStrings(dataSet)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !reflect.DeepEqual(got, []string{"7"}) {
		t.Fatalf("values = %#v, want [7]", got)
	}
}

func TestTableVariableStringsRejectsMultiColumn(t *testing.T) {
	dataSet := &tableQueryDataSet{
		ColumnNames: []string{"cluster", "instance"},
		DataTypes:   []string{"STRING", "STRING"},
		Values:      [][]interface{}{{"a", "b"}},
	}
	if _, err := tableVariableStrings(dataSet); err == nil || !strings.Contains(err.Error(), "exactly one column") {
		t.Fatalf("multi-column error = %v", err)
	}
}

func TestTableVariableValuesCallsExecutor(t *testing.T) {
	var gotDB, gotSQL string
	d := &IoTDBDataSource{
		tableExecutor: func(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
			gotDB, gotSQL = database, sql
			return &tableQueryDataSet{
				ColumnNames: []string{"instance"},
				DataTypes:   []string{"STRING"},
				Values:      [][]interface{}{{"node-a.example.test:9091"}},
			}, nil
		},
	}
	values, err := d.tableVariableValues(context.Background(), "metrics_validation", "SELECT DISTINCT instance FROM sys_cpu_cores")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotDB != "metrics_validation" {
		t.Fatalf("executor database = %q, want metrics_validation", gotDB)
	}
	if gotSQL != "SELECT DISTINCT instance FROM sys_cpu_cores" {
		t.Fatalf("executor sql = %q", gotSQL)
	}
	if !reflect.DeepEqual(values, []string{"node-a.example.test:9091"}) {
		t.Fatalf("values = %#v", values)
	}
}

func TestTableVariableValuesPropagatesExecutorError(t *testing.T) {
	d := &IoTDBDataSource{
		tableExecutor: func(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
			return nil, errors.New("connection refused")
		},
	}
	if _, err := d.tableVariableValues(context.Background(), "db", "SELECT 1"); err == nil || !strings.Contains(err.Error(), "connection refused") {
		t.Fatalf("error = %v, want connection refused", err)
	}
}

func TestGetVariablesTableModelQuery(t *testing.T) {
	var gotDB, gotSQL string
	d := &IoTDBDataSource{
		tableExecutor: func(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
			gotDB, gotSQL = database, sql
			return &tableQueryDataSet{
				ColumnNames: []string{"instance"},
				DataTypes:   []string{"STRING"},
				Values: [][]interface{}{
					{"node-a.example.test:9091"},
					{nil},
					{"node-b.example.test:9091"},
				},
			}, nil
		},
	}
	handler := d.getVariables("", http.DefaultClient)
	request := httptest.NewRequest(http.MethodGet, "/getVariables?url="+url.QueryEscape("http://iotdb:18080")+"&sql="+url.QueryEscape("table:metrics_validation:SELECT DISTINCT instance FROM sys_cpu_cores"), nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	if gotDB != "metrics_validation" || gotSQL != "SELECT DISTINCT instance FROM sys_cpu_cores" {
		t.Fatalf("executor got database %q, sql %q", gotDB, gotSQL)
	}
	var values []string
	if err := json.Unmarshal(recorder.Body.Bytes(), &values); err != nil {
		t.Fatalf("response is not a JSON array: %v; body = %s", err, recorder.Body.String())
	}
	if !reflect.DeepEqual(values, []string{"node-a.example.test:9091", "node-b.example.test:9091"}) {
		t.Fatalf("values = %#v", values)
	}
}

func TestGetVariablesTableModelMultiColumnError(t *testing.T) {
	d := &IoTDBDataSource{
		tableExecutor: func(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
			return &tableQueryDataSet{
				ColumnNames: []string{"cluster", "instance"},
				DataTypes:   []string{"STRING", "STRING"},
				Values:      [][]interface{}{{"a", "b"}},
			}, nil
		},
	}
	handler := d.getVariables("", http.DefaultClient)
	request := httptest.NewRequest(http.MethodGet, "/getVariables?url=x&sql="+url.QueryEscape("table:db:SELECT cluster, instance FROM t"), nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", recorder.Code)
	}
	var body queryResp
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("error body is not JSON: %v; body = %s", err, recorder.Body.String())
	}
	if body.Message == "" || !strings.Contains(body.Message, "exactly one column") {
		t.Fatalf("error message = %q", body.Message)
	}
}

func TestGetVariablesTableModelParseError(t *testing.T) {
	d := &IoTDBDataSource{}
	handler := d.getVariables("", http.DefaultClient)
	request := httptest.NewRequest(http.MethodGet, "/getVariables?url=x&sql="+url.QueryEscape("table:metrics_validation:"), nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}

func TestGetVariablesLegacyPath(t *testing.T) {
	var gotPath, gotBody string
	legacy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		var incoming queryReq
		_ = json.NewDecoder(r.Body).Decode(&incoming)
		gotBody = incoming.Sql
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`["a","b"]`))
	}))
	defer legacy.Close()

	d := &IoTDBDataSource{}
	handler := d.getVariables("Bearer test", http.DefaultClient)
	request := httptest.NewRequest(http.MethodGet, "/getVariables?url="+url.QueryEscape(legacy.URL)+"&sql="+url.QueryEscape("show timeseries"), nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if gotPath != "/grafana/v1/variable" {
		t.Fatalf("legacy path = %q, want /grafana/v1/variable", gotPath)
	}
	if gotBody != "show timeseries" {
		t.Fatalf("legacy body = %q, want show timeseries", gotBody)
	}
	var values []string
	if err := json.Unmarshal(recorder.Body.Bytes(), &values); err != nil || !reflect.DeepEqual(values, []string{"a", "b"}) {
		t.Fatalf("legacy response = %s (err %v)", recorder.Body.String(), err)
	}
}

func TestTableVariableQueryDoesNotLeakCredentials(t *testing.T) {
	const secret = "sup3r-s3cret-password"
	authorization := "Basic " + base64.StdEncoding.EncodeToString([]byte("root:"+secret))
	d := &IoTDBDataSource{
		password: secret,
		tableExecutor: func(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
			return &tableQueryDataSet{
				ColumnNames: []string{"instance"},
				DataTypes:   []string{"STRING"},
				Values:      [][]interface{}{{"node-a.example.test:9091"}},
			}, nil
		},
	}
	handler := d.getVariables(authorization, http.DefaultClient)
	request := httptest.NewRequest(http.MethodGet, "/getVariables?url=x&sql="+url.QueryEscape("table:db:SELECT DISTINCT instance FROM t"), nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	body := recorder.Body.String()
	if strings.Contains(body, secret) {
		t.Fatalf("response leaked the password: %s", body)
	}
	if strings.Contains(body, authorization) {
		t.Fatalf("response leaked the authorization header: %s", body)
	}
	var values []string
	if err := json.Unmarshal(recorder.Body.Bytes(), &values); err != nil || !reflect.DeepEqual(values, []string{"node-a.example.test:9091"}) {
		t.Fatalf("response = %s (err %v)", body, err)
	}
}

func TestExpandVariableMacrosActiveFrom(t *testing.T) {
	fixed := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	d := &IoTDBDataSource{now: func() time.Time { return fixed }}
	sql := "SELECT DISTINCT instance FROM sys_cpu_cores WHERE time >= $__activeFrom AND instance <> ''"
	got := d.expandVariableMacros(sql)
	if strings.Contains(got, "$__activeFrom") {
		t.Fatalf("macro left unexpanded: %s", got)
	}
	want := formatTimeLiteral(fixed.Add(-nodeActiveTTL).UnixMilli())
	if !strings.Contains(got, "time >= "+want) {
		t.Fatalf("expanded SQL = %q, want lower bound %q", got, want)
	}
}

func TestExpandVariableMacrosLeavesOtherSQLAlone(t *testing.T) {
	d := &IoTDBDataSource{now: func() time.Time { return time.UnixMilli(0) }}
	sql := "SELECT DISTINCT cluster FROM sys_cpu_cores WHERE cluster <> ''"
	if got := d.expandVariableMacros(sql); got != sql {
		t.Fatalf("SQL without $__activeFrom changed: %q", got)
	}
}

func TestHandleTableVariableQueryExpandsActiveFrom(t *testing.T) {
	fixed := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	var gotSQL string
	d := &IoTDBDataSource{
		now: func() time.Time { return fixed },
		tableExecutor: func(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
			gotSQL = sql
			return &tableQueryDataSet{
				ColumnNames: []string{"instance"},
				DataTypes:   []string{"STRING"},
				Values:      [][]interface{}{{"node-a.example.test:9091"}},
			}, nil
		},
	}
	handler := d.getVariables("", http.DefaultClient)
	request := httptest.NewRequest(http.MethodGet, "/getVariables?url=x&sql="+url.QueryEscape("table:db:SELECT DISTINCT instance FROM t WHERE time >= $__activeFrom"), nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	if strings.Contains(gotSQL, "$__activeFrom") {
		t.Fatalf("executor received unexpanded macro: %q", gotSQL)
	}
	want := formatTimeLiteral(fixed.Add(-nodeActiveTTL).UnixMilli())
	if !strings.Contains(gotSQL, "time >= "+want) {
		t.Fatalf("executor SQL = %q, want lower bound %q", gotSQL, want)
	}
}
