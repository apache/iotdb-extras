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
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/grafana/grafana-plugin-sdk-go/backend/log"
)

// Resource handlers behind the table-model query editor's database, table and
// column pickers. Each one runs a fixed metadata statement on a pooled native
// session; request parameters only reach the server as quoted identifiers.
// The statements name their database themselves (SHOW TABLES FROM <db>,
// DESC <db>.<table>), so a lookup issues no USE and changes no session state.
// The request context is honoured as far as the native client allows; see
// executeTableStatement.

// tableColumn describes one column of a table-model table as DESC reports it.
type tableColumn struct {
	Name     string `json:"name"`
	DataType string `json:"dataType"`
	Category string `json:"category"`
}

// getTableDatabases lists the databases visible to the datasource user.
func (d *IoTDBDataSource) getTableDatabases() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dataSet, err := d.executeTableStatement(r.Context(), "", "SHOW DATABASES")
		if err != nil {
			writeMetadataError(w, "SHOW DATABASES", err)
			return
		}
		names, err := columnStrings(dataSet, "Database")
		if err != nil {
			writeMetadataError(w, "SHOW DATABASES", err)
			return
		}
		writeJSON(w, names)
	})
}

// getTableTables lists the tables of the database named by the "database"
// query parameter.
func (d *IoTDBDataSource) getTableTables() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The editor trims names when they are entered; here a blank name is only
		// rejected. The database is trimmed as a panel query's USE trims it.
		database := r.URL.Query().Get("database")
		if strings.TrimSpace(database) == "" {
			writeJSONError(w, http.StatusBadRequest, "the database parameter is required")
			return
		}
		dataSet, err := d.executeTableStatement(r.Context(), "", showTablesStatement(database))
		if err != nil {
			writeMetadataError(w, "SHOW TABLES", err)
			return
		}
		names, err := columnStrings(dataSet, "TableName")
		if err != nil {
			writeMetadataError(w, "SHOW TABLES", err)
			return
		}
		writeJSON(w, names)
	})
}

// getTableColumns describes the table named by the "database" and "table"
// query parameters, in the server's column order.
func (d *IoTDBDataSource) getTableColumns() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		database := r.URL.Query().Get("database")
		table := r.URL.Query().Get("table")
		if strings.TrimSpace(database) == "" || strings.TrimSpace(table) == "" {
			writeJSONError(w, http.StatusBadRequest, "the database and table parameters are required")
			return
		}
		dataSet, err := d.executeTableStatement(r.Context(), "", describeTableStatement(database, table))
		if err != nil {
			writeMetadataError(w, "DESC", err)
			return
		}
		columns, err := tableColumns(dataSet)
		if err != nil {
			writeMetadataError(w, "DESC", err)
			return
		}
		writeJSON(w, columns)
	})
}

// showTablesStatement lists the tables of a database without a USE.
func showTablesStatement(database string) string {
	return "SHOW TABLES FROM " + quoteTableIdentifier(strings.TrimSpace(database))
}

// describeTableStatement describes a table by its qualified name, without a
// USE. The table name is used as given: the editor trims it when it is
// entered, and the starter query names the same table.
func describeTableStatement(database, table string) string {
	return "DESC " + quoteTableIdentifier(strings.TrimSpace(database)) + "." + quoteTableIdentifier(table)
}

func writeMetadataError(w http.ResponseWriter, statement string, err error) {
	if errors.Is(err, context.Canceled) {
		// The editor cancels a lookup it no longer needs; that is not a failure.
		log.DefaultLogger.Debug("table-model metadata query cancelled", "statement", statement)
	} else {
		log.DefaultLogger.Error("table-model metadata query failed", "statement", statement, "err", err)
	}
	writeJSONError(w, http.StatusInternalServerError, err.Error())
}

// columnIndex finds a result column by name, ignoring case, since the header
// names are server output rather than something the plugin controls.
func columnIndex(dataSet *tableQueryDataSet, name string) (int, error) {
	for i, column := range dataSet.ColumnNames {
		if strings.EqualFold(column, name) {
			return i, nil
		}
	}
	return -1, fmt.Errorf("the result has no %q column (columns: %s)", name, strings.Join(dataSet.ColumnNames, ", "))
}

// columnStrings returns the non-null values of one named column in server
// order.
func columnStrings(dataSet *tableQueryDataSet, name string) ([]string, error) {
	index, err := columnIndex(dataSet, name)
	if err != nil {
		return nil, err
	}
	values := make([]string, 0, len(dataSet.Values))
	for _, row := range dataSet.Values {
		if index < len(row) && row[index] != nil {
			values = append(values, toString(row[index]))
		}
	}
	return values, nil
}

// tableColumns reads a DESC result into column descriptions.
func tableColumns(dataSet *tableQueryDataSet) ([]tableColumn, error) {
	nameIndex, err := columnIndex(dataSet, "ColumnName")
	if err != nil {
		return nil, err
	}
	typeIndex, err := columnIndex(dataSet, "DataType")
	if err != nil {
		return nil, err
	}
	categoryIndex, err := columnIndex(dataSet, "Category")
	if err != nil {
		return nil, err
	}
	columns := make([]tableColumn, 0, len(dataSet.Values))
	for _, row := range dataSet.Values {
		if nameIndex >= len(row) || row[nameIndex] == nil {
			continue
		}
		column := tableColumn{Name: toString(row[nameIndex])}
		if typeIndex < len(row) && row[typeIndex] != nil {
			column.DataType = toString(row[typeIndex])
		}
		if categoryIndex < len(row) && row[categoryIndex] != nil {
			column.Category = toString(row[categoryIndex])
		}
		columns = append(columns, column)
	}
	return columns, nil
}
