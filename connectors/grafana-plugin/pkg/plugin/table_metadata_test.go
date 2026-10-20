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

	"github.com/apache/iotdb-client-go/v2/client"
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

func TestTableTablesNamesTheRequestedDatabaseInTheStatement(t *testing.T) {
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
	// SHOW TABLES FROM names the database, so no USE is needed on the session.
	if want := []recordedStatement{{database: "", sql: `SHOW TABLES FROM "grafana_demo"`}}; !reflect.DeepEqual(calls, want) {
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
	// An embedded double quote is doubled, so the name stays one identifier, and
	// the table is qualified with its database instead of a USE.
	if want := []recordedStatement{{database: "", sql: `DESC "grafana_demo"."weather""station"`}}; !reflect.DeepEqual(calls, want) {
		t.Fatalf("statements = %v, want %v", calls, want)
	}
}

func TestTableMetadataQuotesEachPartOfTheName(t *testing.T) {
	for _, tc := range []struct {
		name    string
		handler func(d *IoTDBDataSource) http.Handler
		target  string
		want    string
	}{
		{
			name:    "hyphen and upper case in the database",
			handler: (*IoTDBDataSource).getTableTables,
			target:  "/tableTables?database=grafana-Demo",
			want:    `SHOW TABLES FROM "grafana-Demo"`,
		},
		{
			name:    "double quote in the database",
			handler: (*IoTDBDataSource).getTableTables,
			target:  `/tableTables?database=we%22ird`,
			want:    `SHOW TABLES FROM "we""ird"`,
		},
		{
			name:    "hyphen and upper case in both parts",
			handler: (*IoTDBDataSource).getTableColumns,
			target:  "/tableColumns?database=grafana-Demo&table=Weather-Station",
			want:    `DESC "grafana-Demo"."Weather-Station"`,
		},
		{
			// A dot inside a name stays inside its quoted part, so it cannot
			// redirect the lookup to another database.
			name:    "dot and double quote inside the names",
			handler: (*IoTDBDataSource).getTableColumns,
			target:  `/tableColumns?database=a.b&table=c%22.%22d`,
			want:    `DESC "a.b"."c"".""d"`,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls []recordedStatement
			d := metadataDataSource(&tableQueryDataSet{ColumnNames: []string{"TableName", "ColumnName", "DataType", "Category"}}, nil, &calls)

			if recorder := serveMetadata(t, tc.handler(d), tc.target); recorder.Code != http.StatusOK {
				t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
			}
			if want := []recordedStatement{{database: "", sql: tc.want}}; !reflect.DeepEqual(calls, want) {
				t.Fatalf("statements = %v, want %v", calls, want)
			}
		})
	}
}

func TestTableColumnsUsesTheNamesAsGiven(t *testing.T) {
	var calls []recordedStatement
	d := metadataDataSource(&tableQueryDataSet{
		ColumnNames: []string{"ColumnName", "DataType", "Category"},
		Values:      [][]interface{}{{"time", "TIMESTAMP", "TIME"}},
	}, nil, &calls)

	recorder := serveMetadata(t, d.getTableColumns(), `/tableColumns?database=%20grafana_demo%20&table=weather%20`)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	// The editor trims names when they are entered, so the handler does not trim
	// the table again: the columns it lists are those of the table the starter
	// query names. The database is trimmed, as a panel query's USE trims it.
	if want := []recordedStatement{{database: "", sql: `DESC "grafana_demo"."weather "`}}; !reflect.DeepEqual(calls, want) {
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

func TestTableMetadataStopsWhenTheEditorCancelsTheRequest(t *testing.T) {
	acquired := 0
	d := sessionDataSource(&acquired, func() client.ITableSession { return &fakeTableSession{} })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	for _, tc := range []struct {
		handler http.Handler
		target  string
	}{
		{d.getTableDatabases(), "/tableDatabases"},
		{d.getTableTables(), "/tableTables?database=grafana_demo"},
		{d.getTableColumns(), "/tableColumns?database=grafana_demo&table=cpu"},
	} {
		recorder := httptest.NewRecorder()
		tc.handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, tc.target, nil).WithContext(ctx))
		if recorder.Code != http.StatusInternalServerError {
			t.Errorf("%s: status = %d, want %d", tc.target, recorder.Code, http.StatusInternalServerError)
		}
	}
	// The handlers pass the request's context on, so an aborted lookup never
	// takes one of the pool's sessions from the panel queries.
	if acquired != 0 {
		t.Fatalf("cancelled lookups took %d session(s)", acquired)
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
