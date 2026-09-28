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

package plugin

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

type recordedStatement struct {
	database string
	sql      string
}

// metadataDataSource returns a datasource whose table-model statements are
// answered by result and recorded into calls.
func metadataDataSource(result *tableQueryDataSet, err error, calls *[]recordedStatement) *IoTDBDataSource {
	return &IoTDBDataSource{
		tableExecutor: func(_ context.Context, database, sql string) (*tableQueryDataSet, error) {
			*calls = append(*calls, recordedStatement{database: database, sql: sql})
			return result, err
		},
	}
}

func serveMetadata(t *testing.T, handler http.Handler, target string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, target, nil))
	return recorder
}

func TestTableDatabasesReturnsTheDatabaseColumn(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(&tableQueryDataSet{
		ColumnNames: []string{"Database", "TTL(ms)", "SchemaReplicationFactor"},
		Values: [][]interface{}{
			{"grafana_demo", "INF", int32(1)},
			{"information_schema", "INF", nil},
		},
	}, nil, &calls)

	recorder := serveMetadata(t, d.getTableDatabases(), "/tableDatabases")

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	var names []string
	if err := json.Unmarshal(recorder.Body.Bytes(), &names); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if want := []string{"grafana_demo", "information_schema"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("names = %v, want %v", names, want)
	}
	if want := []recordedStatement{{database: "", sql: "SHOW DATABASES"}}; !reflect.DeepEqual(calls, want) {
		t.Fatalf("statements = %v, want %v", calls, want)
	}
}

func TestTableTablesRunsShowTablesInTheRequestedDatabase(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(&tableQueryDataSet{
		ColumnNames: []string{"TableName", "TTL(ms)"},
		Values:      [][]interface{}{{"weather station", "INF"}, {"cpu", "INF"}},
	}, nil, &calls)

	recorder := serveMetadata(t, d.getTableTables(), "/tableTables?database=grafana_demo")

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	var names []string
	if err := json.Unmarshal(recorder.Body.Bytes(), &names); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if want := []string{"weather station", "cpu"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("names = %v, want %v", names, want)
	}
	if want := []recordedStatement{{database: "grafana_demo", sql: "SHOW TABLES"}}; !reflect.DeepEqual(calls, want) {
		t.Fatalf("statements = %v, want %v", calls, want)
	}
}

func TestTableColumnsQuotesTheTableName(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(&tableQueryDataSet{
		ColumnNames: []string{"ColumnName", "DataType", "Category"},
		Values: [][]interface{}{
			{"time", "TIMESTAMP", "TIME"},
			{"region", "STRING", "TAG"},
			{"model", "STRING", "ATTRIBUTE"},
			{"temperature", "FLOAT", "FIELD"},
		},
	}, nil, &calls)

	recorder := serveMetadata(t, d.getTableColumns(), `/tableColumns?database=grafana_demo&table=weather%22station`)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	var columns []tableColumn
	if err := json.Unmarshal(recorder.Body.Bytes(), &columns); err != nil {
		t.Fatalf("decode: %v", err)
	}
	want := []tableColumn{
		{Name: "time", DataType: "TIMESTAMP", Category: "TIME"},
		{Name: "region", DataType: "STRING", Category: "TAG"},
		{Name: "model", DataType: "STRING", Category: "ATTRIBUTE"},
		{Name: "temperature", DataType: "FLOAT", Category: "FIELD"},
	}
	if !reflect.DeepEqual(columns, want) {
		t.Fatalf("columns = %v, want %v", columns, want)
	}
	// An embedded double quote is doubled, so the name stays one identifier.
	if want := []recordedStatement{{database: "grafana_demo", sql: `DESC "weather""station"`}}; !reflect.DeepEqual(calls, want) {
		t.Fatalf("statements = %v, want %v", calls, want)
	}
}

func TestTableMetadataRequiresItsParameters(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(&tableQueryDataSet{}, nil, &calls)

	for _, tc := range []struct {
		handler http.Handler
		target  string
	}{
		{d.getTableTables(), "/tableTables"},
		{d.getTableTables(), "/tableTables?database=%20"},
		{d.getTableColumns(), "/tableColumns?database=grafana_demo"},
		{d.getTableColumns(), "/tableColumns?table=cpu"},
	} {
		if recorder := serveMetadata(t, tc.handler, tc.target); recorder.Code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want %d", tc.target, recorder.Code, http.StatusBadRequest)
		}
	}
	if len(calls) != 0 {
		t.Fatalf("a request missing its parameters reached the server: %v", calls)
	}
}

func TestTableMetadataReportsServerErrors(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(nil, errors.New("550: Table 'grafana_demo.nope' does not exist"), &calls)

	recorder := serveMetadata(t, d.getTableColumns(), "/tableColumns?database=grafana_demo&table=nope")

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusInternalServerError)
	}
	if !strings.Contains(recorder.Body.String(), "does not exist") {
		t.Fatalf("body = %s, want the server message", recorder.Body.String())
	}
}

func TestTableMetadataRejectsAnUnexpectedResultShape(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(&tableQueryDataSet{
		ColumnNames: []string{"name"},
		Values:      [][]interface{}{{"x"}},
	}, nil, &calls)

	if recorder := serveMetadata(t, d.getTableDatabases(), "/tableDatabases"); recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusInternalServerError)
	}
}
