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
	"errors"
	"testing"
	"time"

	"github.com/grafana/grafana-plugin-sdk-go/data"
)

func ts(ms int64) time.Time {
	return time.UnixMilli(ms).UTC()
}

func TestExpandTableMacros(t *testing.T) {
	const from int64 = 1600000000000 // 2020-09-13T12:26:40.000+00:00
	const to int64 = 1600000001000   // 2020-09-13T12:26:41.000+00:00
	const fromLit = "2020-09-13T12:26:40.000+00:00"
	const toLit = "2020-09-13T12:26:41.000+00:00"

	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "timeFilter with explicit column",
			in:   "SELECT time, s0 FROM db.t WHERE $__timeFilter(time)",
			want: "SELECT time, s0 FROM db.t WHERE (time >= " + fromLit + " AND time <= " + toLit + ")",
		},
		{
			name: "timeFilter defaults to time column when empty",
			in:   "SELECT * FROM db.t WHERE $__timeFilter()",
			want: "SELECT * FROM db.t WHERE (time >= " + fromLit + " AND time <= " + toLit + ")",
		},
		{
			name: "timeFrom and timeTo",
			in:   "SELECT * FROM db.t WHERE time >= $__timeFrom AND time <= $__timeTo",
			want: "SELECT * FROM db.t WHERE time >= " + fromLit + " AND time <= " + toLit,
		},
		{
			name: "function form timeFrom and timeTo",
			in:   "SELECT * FROM db.t WHERE time >= $__timeFrom() AND time <= $__timeTo( )",
			want: "SELECT * FROM db.t WHERE time >= " + fromLit + " AND time <= " + toLit,
		},
		{
			name: "timeFilter with one nested paren level",
			in:   "SELECT * FROM db.t WHERE $__timeFilter(cast(x))",
			want: "SELECT * FROM db.t WHERE (cast(x) >= " + fromLit + " AND cast(x) <= " + toLit + ")",
		},
		{
			name: "longer identifier is not mangled",
			in:   "SELECT $__timeFromage FROM db.t",
			want: "SELECT $__timeFromage FROM db.t",
		},
		{
			name: "no macros is unchanged",
			in:   "SELECT * FROM db.t",
			want: "SELECT * FROM db.t",
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := expandTableMacros(c.in, from, to)
			if got != c.want {
				t.Fatalf("expandTableMacros() = %q, want %q", got, c.want)
			}
		})
	}
}

func TestQuoteTableIdentifier(t *testing.T) {
	if got := quoteTableIdentifier("test"); got != `"test"` {
		t.Fatalf("plain identifier = %q", got)
	}
	if got := quoteTableIdentifier(`we"ird`); got != `"we""ird"` {
		t.Fatalf("embedded quote = %q", got)
	}
}

// fakeResultSet implements tableResultSet over an in-memory row list, standing
// in for the native client's SessionDataSet (1-based column indexes).
type fakeResultSet struct {
	names  []string
	types  []string
	rows   [][]interface{}
	cursor int
	err    error
	closed bool
}

func (f *fakeResultSet) Next() (bool, error) {
	if f.err != nil {
		return false, f.err
	}
	if f.cursor >= len(f.rows) {
		return false, nil
	}
	f.cursor++
	return true, nil
}

func (f *fakeResultSet) GetColumnNames() []string { return f.names }
func (f *fakeResultSet) GetColumnTypes() []string { return f.types }

func (f *fakeResultSet) GetObjectByIndex(columnIndex int32) (interface{}, error) {
	return f.rows[f.cursor-1][columnIndex-1], nil
}

func (f *fakeResultSet) Close() error {
	f.closed = true
	return nil
}

func TestFetchTableDataSet(t *testing.T) {
	rs := &fakeResultSet{
		names: []string{"time", "device", "value"},
		types: []string{"TIMESTAMP", "STRING", "DOUBLE"},
		rows: [][]interface{}{
			{ts(1000), "d1", float64(1.5)},
			{ts(2000), nil, float64(2.5)}, // null cell passes through
		},
	}
	dataSet, err := fetchTableDataSet(rs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(dataSet.Values) != 2 || len(dataSet.ColumnNames) != 3 {
		t.Fatalf("unexpected shape: %d rows, %d columns", len(dataSet.Values), len(dataSet.ColumnNames))
	}
	if dataSet.Values[0][1] != "d1" || dataSet.Values[1][1] != nil {
		t.Fatalf("cells not carried over: %#v", dataSet.Values)
	}
	if tv, ok := dataSet.Values[1][0].(time.Time); !ok || !tv.Equal(ts(2000)) {
		t.Fatalf("time cell = %#v, want %v", dataSet.Values[1][0], ts(2000))
	}
}

func TestFetchTableDataSetPropagatesError(t *testing.T) {
	rs := &fakeResultSet{names: []string{"a"}, types: []string{"INT64"}, err: errors.New("broken pipe")}
	if _, err := fetchTableDataSet(rs); err == nil {
		t.Fatalf("expected the iteration error to propagate")
	}
}

// TestBuildTableFrameRowOrientation pins the fetch orientation contract: the
// dataset rows are row-major (values[row][col]), so a field must gather a
// single column across every row, with the client's native Go value types.
func TestBuildTableFrameRowOrientation(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "i", "d", "b", "s"},
		DataTypes:   []string{"TIMESTAMP", "INT64", "DOUBLE", "BOOLEAN", "TEXT"},
		Values: [][]interface{}{
			{ts(1600000000000), int64(42), float64(3.5), true, "hello"},
			{ts(1600000001000), int64(43), float64(4.5), false, "world"},
		},
	}

	frame := buildTableFrame(ds)
	if len(frame.Fields) != 5 {
		t.Fatalf("expected 5 fields, got %d", len(frame.Fields))
	}
	for _, f := range frame.Fields {
		if f.Len() != 2 {
			t.Fatalf("field %q has len %d, want 2 (row count)", f.Name, f.Len())
		}
	}

	if v, ok := frame.Fields[0].At(1).(*time.Time); !ok || v == nil || !v.Equal(ts(1600000001000)) {
		t.Fatalf("time[1] = %#v", frame.Fields[0].At(1))
	}
	if v := frame.Fields[1].At(0).(*int64); *v != 42 {
		t.Fatalf("i[0] = %d, want 42", *v)
	}
	if v := frame.Fields[2].At(1).(*float64); *v != 4.5 {
		t.Fatalf("d[1] = %v, want 4.5", *v)
	}
	if v := frame.Fields[3].At(1).(*bool); *v != false {
		t.Fatalf("b[1] = %v, want false", *v)
	}
	if v := frame.Fields[4].At(1).(*string); *v != "world" {
		t.Fatalf("s[1] = %q, want world", *v)
	}
}

// TestBuildTableFieldNativeTypeCoercions pins the client-type mapping: INT32
// widens to int64, FLOAT widens to float64, an INT64 above 2^53 stays exact
// (native int64, no float roundtrip), DATE renders as yyyy-MM-dd and BLOB as
// 0x-prefixed hex — the same rendering the REST transport produced.
func TestBuildTableFieldNativeTypeCoercions(t *testing.T) {
	if v := buildTableField("i", "INT32", []interface{}{int32(7)}).At(0).(*int64); *v != 7 {
		t.Fatalf("INT32 = %d, want 7", *v)
	}
	if v := buildTableField("f", "FLOAT", []interface{}{float32(1.5)}).At(0).(*float64); *v != 1.5 {
		t.Fatalf("FLOAT = %v, want 1.5", *v)
	}
	if v := buildTableField("big", "INT64", []interface{}{int64(9007199254740993)}).At(0).(*int64); *v != 9007199254740993 {
		t.Fatalf("INT64 precision lost: %d", *v)
	}
	date := time.Date(2025, 7, 14, 0, 0, 0, 0, time.UTC)
	if v := buildTableField("e", "DATE", []interface{}{date}).At(0).(*string); *v != "2025-07-14" {
		t.Fatalf("DATE = %q, want 2025-07-14", *v)
	}
	if v := buildTableField("d", "BLOB", []interface{}{[]byte{0xca, 0xfe, 0xba, 0xbe}}).At(0).(*string); *v != "0xcafebabe" {
		t.Fatalf("BLOB = %q, want 0xcafebabe", *v)
	}
}

func TestBuildTableFieldNullsBecomeNilPointers(t *testing.T) {
	f := buildTableField("i", "INT64", []interface{}{int64(1), nil, int64(3)})
	if f.Len() != 3 {
		t.Fatalf("expected 3 values, got %d", f.Len())
	}
	if v, ok := f.At(1).(*int64); !ok || v != nil {
		t.Fatalf("null value should be a nil *int64, got %#v", f.At(1))
	}
	if v, ok := f.At(0).(*int64); !ok || v == nil || *v != 1 {
		t.Fatalf("first value = %#v, want *int64(1)", f.At(0))
	}
}

func TestBuildTableFieldUnknownTypeRendersString(t *testing.T) {
	f := buildTableField("x", "SOMETHING_NEW", []interface{}{int64(7), "raw", nil})
	if f.Len() != 3 {
		t.Fatalf("expected 3 values, got %d", f.Len())
	}
	if v, ok := f.At(0).(*string); !ok || v == nil || *v != "7" {
		t.Fatalf("numeric coerced to string = %#v, want *string(7)", f.At(0))
	}
	if v, ok := f.At(1).(*string); !ok || v == nil || *v != "raw" {
		t.Fatalf("string value = %#v, want *string(raw)", f.At(1))
	}
	if v, ok := f.At(2).(*string); !ok || v != nil {
		t.Fatalf("null value should be nil *string, got %#v", f.At(2))
	}
}

// TestBuildTableFrameRaggedRowIsSafe checks that a row shorter than the column
// header does not panic and yields equal-length, null-padded fields.
func TestBuildTableFrameRaggedRowIsSafe(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "s0"},
		DataTypes:   []string{"TIMESTAMP", "TEXT"},
		Values: [][]interface{}{
			{ts(1), "a"},
			{ts(2)}, // ragged: missing s0
		},
	}
	frame := buildTableFrame(ds)
	if len(frame.Fields) != 2 {
		t.Fatalf("expected 2 fields, got %d", len(frame.Fields))
	}
	if frame.Fields[0].Len() != 2 || frame.Fields[1].Len() != 2 {
		t.Fatalf("fields must be equal length (2), got %d and %d", frame.Fields[0].Len(), frame.Fields[1].Len())
	}
	if v, ok := frame.Fields[1].At(1).(*string); !ok || v != nil {
		t.Fatalf("missing ragged cell should be nil *string, got %#v", frame.Fields[1].At(1))
	}
}

// TestBuildTableResponseFrameLongToWide pins the multi-device path: a long
// result (time + tag + value), even arriving unsorted, must come back as one
// labeled series per tag value so a time-series panel draws separate lines.
func TestBuildTableResponseFrameLongToWide(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "device", "temperature"},
		DataTypes:   []string{"TIMESTAMP", "STRING", "DOUBLE"},
		Values: [][]interface{}{
			{ts(2000), "d2", float64(22.5)},
			{ts(1000), "d1", float64(11.0)},
			{ts(1000), "d2", float64(21.0)},
			{ts(2000), "d1", float64(12.0)},
		},
	}

	frame := buildTableResponseFrame(ds, "")
	if len(frame.Fields) != 3 {
		t.Fatalf("expected time + one series per device (3 fields), got %d", len(frame.Fields))
	}
	if frame.Fields[0].Len() != 2 {
		t.Fatalf("expected 2 wide rows, got %d", frame.Fields[0].Len())
	}
	byDevice := map[string][]float64{}
	for _, f := range frame.Fields[1:] {
		dev := f.Labels["device"]
		if dev == "" {
			t.Fatalf("value field %q has no device label: %v", f.Name, f.Labels)
		}
		var vals []float64
		for i := 0; i < f.Len(); i++ {
			v, ok := f.At(i).(*float64)
			if !ok || v == nil {
				t.Fatalf("field %q row %d = %#v, want *float64", f.Name, i, f.At(i))
			}
			vals = append(vals, *v)
		}
		byDevice[dev] = vals
	}
	if v := byDevice["d1"]; len(v) != 2 || v[0] != 11.0 || v[1] != 12.0 {
		t.Fatalf("d1 series = %v, want [11 12]", v)
	}
	if v := byDevice["d2"]; len(v) != 2 || v[0] != 21.0 || v[1] != 22.5 {
		t.Fatalf("d2 series = %v, want [21 22.5]", v)
	}
}

// TestBuildTableResponseFrameTableFormatPreservesOrder pins that the Table
// format neither re-sorts rows (the user's ORDER BY wins) nor pivots tags
// into labels.
func TestBuildTableResponseFrameTableFormatPreservesOrder(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "device", "temperature"},
		DataTypes:   []string{"TIMESTAMP", "STRING", "DOUBLE"},
		Values: [][]interface{}{
			{ts(2000), "d2", float64(22.5)},
			{ts(1000), "d1", float64(11.0)},
		},
	}

	frame := buildTableResponseFrame(ds, tableFormatTable)
	if len(frame.Fields) != 3 {
		t.Fatalf("expected 3 plain fields, got %d", len(frame.Fields))
	}
	first, ok := frame.Fields[0].At(0).(*time.Time)
	if !ok || first == nil || !first.Equal(ts(2000)) {
		t.Fatalf("row order changed: first time = %#v, want t=2000ms", frame.Fields[0].At(0))
	}
	if _, ok := frame.Fields[1].At(0).(*string); !ok {
		t.Fatalf("tag column should stay a plain string field in Table format")
	}
}

// TestBuildTableResponseFrameNullTimeFallsBack pins the safety net: when the
// long-to-wide pivot cannot apply (here: a null timestamp), the plain frame is
// returned instead of an error or a panic.
func TestBuildTableResponseFrameNullTimeFallsBack(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "device", "temperature"},
		DataTypes:   []string{"TIMESTAMP", "STRING", "DOUBLE"},
		Values: [][]interface{}{
			{ts(1000), "d1", float64(11.0)},
			{nil, "d2", float64(21.0)},
		},
	}

	frame := buildTableResponseFrame(ds, tableFormatTimeSeries)
	if len(frame.Fields) != 3 {
		t.Fatalf("expected plain 3-field fallback frame, got %d fields", len(frame.Fields))
	}
	if frame.TimeSeriesSchema().Type != data.TimeSeriesTypeLong {
		t.Fatalf("fallback frame should still be the long-shaped original")
	}
}

func TestTableRPCEndpoint(t *testing.T) {
	cases := []struct {
		name       string
		rpcAddress string
		url        string
		wantHost   string
		wantPort   string
	}{
		{name: "derived from http url", url: "http://192.168.1.10:18080", wantHost: "192.168.1.10", wantPort: "6667"},
		{name: "derived from url with trailing slash", url: "http://iotdb.example.com:18080/", wantHost: "iotdb.example.com", wantPort: "6667"},
		{name: "derived from bare host and rest port", url: "192.168.1.10:18080", wantHost: "192.168.1.10", wantPort: "6667"},
		{name: "explicit host and port", rpcAddress: "10.0.0.5:7777", url: "http://x:18080", wantHost: "10.0.0.5", wantPort: "7777"},
		{name: "explicit bare host gets default port", rpcAddress: "10.0.0.5", url: "http://x:18080", wantHost: "10.0.0.5", wantPort: "6667"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			d := &IoTDBDataSource{Ulr: c.url, RPCAddress: c.rpcAddress}
			host, port, err := d.tableRPCEndpoint()
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if host != c.wantHost || port != c.wantPort {
				t.Fatalf("endpoint = %s:%s, want %s:%s", host, port, c.wantHost, c.wantPort)
			}
		})
	}
}
