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
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/grafana/grafana-plugin-sdk-go/backend"
	"github.com/grafana/grafana-plugin-sdk-go/backend/log"
	"github.com/grafana/grafana-plugin-sdk-go/data"
)

// TableModelSqlType is the QueryEditor mode that sends standard table-model SQL
// straight to IoTDB's table REST interface, as opposed to the tree-model modes
// ("SQL: Full Customized" / "SQL: Drop-down List") that build root.* paths.
const TableModelSqlType = "SQL: Table Model"

// Result formats for the table-model mode. Time series (the default) sorts
// rows ascending by the first TIMESTAMP column and pivots a long-shaped result
// (time + tag columns + value columns) into one labeled series per tag
// combination; Table returns the rows exactly as the server sent them.
const (
	tableFormatTimeSeries = "Time series"
	tableFormatTable      = "Table"
)

// tableQueryPath is IoTDB's table-model query endpoint (see the /rest/table/v1
// RestApi). It accepts a standard SQL statement plus an optional database
// context and returns a column-major QueryDataSet.
const tableQueryPath = "/rest/table/v1/query"

// tableQueryReq is the request body for tableQueryPath. Field names match the
// generated table/v1 SQL model (database / sql / row_limit).
type tableQueryReq struct {
	Database string `json:"database,omitempty"`
	Sql      string `json:"sql"`
	RowLimit *int   `json:"row_limit,omitempty"`
}

// tableQueryDataSet mirrors the table/v1 QueryDataSet response. Its values are
// ROW-major (values[row][col]) because the table endpoint transposes before
// serializing, unlike the tree-model endpoints. snake_case JSON. On failure
// IoTDB returns an ExecutionStatus instead, whose code/message are captured
// here so a non-zero code surfaces as an error.
type tableQueryDataSet struct {
	ColumnNames []string        `json:"column_names"`
	DataTypes   []string        `json:"data_types"`
	Values      [][]interface{} `json:"values"`
	Code        int32           `json:"code"`
	Message     string          `json:"message"`
}

// timeFilterRe matches Grafana's $__timeFilter(column) macro; the column is
// optional and defaults to "time". One level of nested parentheses is allowed
// so expressions like $__timeFilter(cast(x)) survive intact.
var timeFilterRe = regexp.MustCompile(`\$__timeFilter\(\s*((?:[^()]|\([^()]*\))*?)\s*\)`)

// timeFromRe / timeToRe match $__timeFrom / $__timeTo, with or without the
// trailing () that Grafana's SQL data sources use ($__timeFrom()).
var (
	timeFromRe = regexp.MustCompile(`\$__timeFrom\b(?:\s*\(\s*\))?`)
	timeToRe   = regexp.MustCompile(`\$__timeTo\b(?:\s*\(\s*\))?`)
)

// timestampUnits maps the datasource's timestampPrecision option (which must
// match the server's timestamp_precision property) to conversion factors:
// unitsPerMs scales the panel's epoch-ms range into server units for the time
// macros, nsPerUnit scales raw TIMESTAMP values into nanoseconds for Grafana.
func timestampUnits(precision string) (unitsPerMs int64, nsPerUnit int64) {
	switch strings.TrimSpace(strings.ToLower(precision)) {
	case "us":
		return 1000, int64(time.Microsecond)
	case "ns":
		return 1000000, 1
	default: // ms is IoTDB's default timestamp_precision
		return 1, int64(time.Millisecond)
	}
}

// expandTableMacros rewrites the Grafana time macros a dashboard author can put
// in table-model SQL into concrete bounds for the panel's range:
//
//	$__timeFilter(col)      -> (col >= <from> AND col <= <to>)
//	$__timeFrom[()]         -> <from>
//	$__timeTo[()]           -> <to>
//
// Bounds are epoch values in the server's timestamp precision (milliseconds
// unless the datasource says otherwise), matching how IoTDB compares integer
// literals against TIMESTAMP columns.
func expandTableMacros(sql string, start int64, end int64) string {
	from := strconv.FormatInt(start, 10)
	to := strconv.FormatInt(end, 10)
	sql = timeFilterRe.ReplaceAllStringFunc(sql, func(m string) string {
		col := strings.TrimSpace(timeFilterRe.FindStringSubmatch(m)[1])
		if col == "" {
			col = "time"
		}
		return "(" + col + " >= " + from + " AND " + col + " <= " + to + ")"
	})
	sql = timeFromRe.ReplaceAllString(sql, from)
	sql = timeToRe.ReplaceAllString(sql, to)
	return sql
}

// queryTableModel runs a table-model SQL query against IoTDB's table REST
// endpoint and turns the column-major QueryDataSet into a Grafana data frame.
func (d *IoTDBDataSource) queryTableModel(ctx context.Context, qp *queryParam, authorization string) backend.DataResponse {
	response := backend.DataResponse{}

	// The panel range arrives in epoch ms; the server compares integer time
	// literals (and returns TIMESTAMP values) in its own configured precision.
	unitsPerMs, nsPerUnit := timestampUnits(d.TimestampPrecision)

	sql := expandTableMacros(qp.Sql, qp.StartTime*unitsPerMs, qp.EndTime*unitsPerMs)
	reqBody := tableQueryReq{Database: qp.Database, Sql: sql}
	qpJson, err := json.Marshal(reqBody)
	if err != nil {
		response.Error = err
		return response
	}

	dataSourceUrl := DataSourceUrlHandler(d.Ulr)
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, dataSourceUrl+tableQueryPath, bytes.NewReader(qpJson))
	if err != nil {
		response.Error = err
		return response
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Add("Authorization", authorization)

	rsp, err := d.httpClient.Do(request)
	if err != nil {
		response.Error = errors.New("Data source is not working properly")
		log.DefaultLogger.Error("Data source is not working properly", "err", err)
		return response
	}
	defer rsp.Body.Close()

	body, err := io.ReadAll(rsp.Body)
	if err != nil {
		response.Error = errors.New("Data source is not working properly")
		log.DefaultLogger.Error("Failed to read response body", "err", err)
		return response
	}

	dataSet, err := parseTableQueryResponse(body)
	if err != nil {
		response.Error = err
		log.DefaultLogger.Error("Parsing JSON error", "err", err)
		return response
	}
	if dataSet.Code > 0 {
		response.Error = errors.New(dataSet.Message)
		log.DefaultLogger.Error(dataSet.Message)
		return response
	}

	response.Frames = append(response.Frames, buildTableResponseFrame(dataSet, qp.Format, nsPerUnit))
	return response
}

// buildTableResponseFrame turns a decoded dataset into the response frame,
// honoring the query's format. In the default Time series format the rows are
// sorted ascending by the first TIMESTAMP column and a long-shaped result
// (time + string tag columns + value columns) is pivoted into one labeled
// series per tag combination, so a multi-device query renders as separate
// lines instead of one interleaved series; when the pivot does not apply (or
// fails, e.g. on a null timestamp) the plain frame is returned. The Table
// format preserves the server's row order untouched.
func buildTableResponseFrame(dataSet *tableQueryDataSet, format string, nsPerUnit int64) *data.Frame {
	// Anything that is not explicitly the Table format gets the default
	// time-series treatment, including queries saved before FORMAT existed.
	isTimeSeries := !strings.EqualFold(format, tableFormatTable)
	if isTimeSeries {
		sortRowsByFirstTimestamp(dataSet)
	}
	frame := buildTableFrame(dataSet, nsPerUnit)
	if isTimeSeries && frame.TimeSeriesSchema().Type == data.TimeSeriesTypeLong {
		if wide, err := data.LongToWide(frame, nil); err == nil {
			frame = wide
		}
	}
	return frame
}

// sortRowsByFirstTimestamp stably sorts the row-major values ascending by the
// first TIMESTAMP column, which both time-series rendering and the long-to-wide
// pivot require. Rows whose time cell is null sort last.
func sortRowsByFirstTimestamp(dataSet *tableQueryDataSet) {
	timeCol := -1
	for i, t := range dataSet.DataTypes {
		if strings.EqualFold(t, "TIMESTAMP") {
			timeCol = i
			break
		}
	}
	if timeCol < 0 {
		return
	}
	sort.SliceStable(dataSet.Values, func(a, b int) bool {
		va, okA := rowTimeAt(dataSet.Values[a], timeCol)
		vb, okB := rowTimeAt(dataSet.Values[b], timeCol)
		if okA != okB {
			return okA
		}
		return okA && va < vb
	})
}

func rowTimeAt(row []interface{}, col int) (int64, bool) {
	if col >= len(row) {
		return 0, false
	}
	return toInt64(row[col])
}

// parseTableQueryResponse unmarshals a table-model query response, surfacing an
// error object (code/message) as a Go error when the request did not succeed.
// It decodes with UseNumber so that INT64/TIMESTAMP values beyond 2^53 keep
// their precision (a plain interface{} decode would coerce them to float64).
func parseTableQueryResponse(body []byte) (*tableQueryDataSet, error) {
	var dataSet tableQueryDataSet
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	if err := decoder.Decode(&dataSet); err != nil {
		return nil, errors.New("Parsing JSON error")
	}
	return &dataSet, nil
}

// buildTableFrame converts a row-major QueryDataSet into a Grafana frame, one
// typed field per column driven by the response's data_types (so we no longer
// have to guess types the way the tree-model path does). The endpoint returns
// values row-major (values[row][col]); each output field gathers a single
// column across all rows, so every field ends up the same length (the row
// count) that Grafana requires.
func buildTableFrame(dataSet *tableQueryDataSet, nsPerUnit int64) *data.Frame {
	frame := data.NewFrame("response")
	rowCount := len(dataSet.Values)
	for col := 0; col < len(dataSet.ColumnNames); col++ {
		name := dataSet.ColumnNames[col]
		dataType := ""
		if col < len(dataSet.DataTypes) {
			dataType = dataSet.DataTypes[col]
		}
		columnValues := make([]interface{}, rowCount)
		for row := 0; row < rowCount; row++ {
			if col < len(dataSet.Values[row]) {
				columnValues[row] = dataSet.Values[row][col]
			}
		}
		frame.Fields = append(frame.Fields, buildTableField(name, dataType, columnValues, nsPerUnit))
	}
	return frame
}

// buildTableField turns one column's raw JSON values into a typed, nullable
// Grafana field selected by the IoTDB data type. Numbers arrive as json.Number
// (the body is decoded with UseNumber), so integer, timestamp and float columns
// are coerced through toInt64 / toFloat64. TIMESTAMP values are raw epoch
// ticks in the server's precision; nsPerUnit scales them to nanoseconds.
func buildTableField(name string, dataType string, values []interface{}, nsPerUnit int64) *data.Field {
	switch strings.ToUpper(dataType) {
	case "TIMESTAMP":
		out := make([]*time.Time, len(values))
		for i, v := range values {
			if ticks, ok := toInt64(v); ok {
				t := time.Unix(0, ticks*nsPerUnit)
				out[i] = &t
			}
		}
		return data.NewField(name, nil, out)
	case "INT32", "INT64":
		out := make([]*int64, len(values))
		for i, v := range values {
			if n, ok := toInt64(v); ok {
				value := n
				out[i] = &value
			}
		}
		return data.NewField(name, nil, out)
	case "FLOAT", "DOUBLE":
		out := make([]*float64, len(values))
		for i, v := range values {
			if f, ok := toFloat64(v); ok {
				value := f
				out[i] = &value
			}
		}
		return data.NewField(name, nil, out)
	case "BOOLEAN":
		out := make([]*bool, len(values))
		for i, v := range values {
			if b, ok := v.(bool); ok {
				value := b
				out[i] = &value
			}
		}
		return data.NewField(name, nil, out)
	default:
		// TEXT, STRING, BLOB and anything unrecognised render as strings.
		out := make([]*string, len(values))
		for i, v := range values {
			if v == nil {
				continue
			}
			if s, ok := v.(string); ok {
				value := s
				out[i] = &value
			} else {
				value := toString(v)
				out[i] = &value
			}
		}
		return data.NewField(name, nil, out)
	}
}

// toInt64 coerces a JSON-decoded numeric value to int64. encoding/json decodes
// numbers into float64 by default; json.Number is handled too for callers that
// opt into it.
func toInt64(v interface{}) (int64, bool) {
	switch n := v.(type) {
	case float64:
		return int64(n), true
	case json.Number:
		if i, err := n.Int64(); err == nil {
			return i, true
		}
		if f, err := n.Float64(); err == nil {
			return int64(f), true
		}
	case int64:
		return n, true
	}
	return 0, false
}

// toFloat64 coerces a JSON-decoded numeric value to float64, handling both the
// float64 (plain decode) and json.Number (UseNumber decode) representations.
func toFloat64(v interface{}) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case json.Number:
		if f, err := n.Float64(); err == nil {
			return f, true
		}
	}
	return 0, false
}

// toString renders a non-string scalar for a text column without losing it.
func toString(v interface{}) string {
	switch value := v.(type) {
	case string:
		return value
	case float64:
		return strconv.FormatFloat(value, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(value)
	case json.Number:
		return value.String()
	default:
		b, err := json.Marshal(v)
		if err != nil {
			return ""
		}
		return string(b)
	}
}
