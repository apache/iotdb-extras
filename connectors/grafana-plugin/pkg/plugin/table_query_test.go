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
	"testing"
	"time"

	"github.com/grafana/grafana-plugin-sdk-go/data"
)

// msNs is the TIMESTAMP tick-to-nanosecond factor for a default (ms) server.
const msNs = int64(time.Millisecond)

func TestExpandTableMacros(t *testing.T) {
	const from int64 = 1000
	const to int64 = 2000

	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "timeFilter with explicit column",
			in:   "SELECT time, s0 FROM db.t WHERE $__timeFilter(time)",
			want: "SELECT time, s0 FROM db.t WHERE (time >= 1000 AND time <= 2000)",
		},
		{
			name: "timeFilter defaults to time column when empty",
			in:   "SELECT * FROM db.t WHERE $__timeFilter()",
			want: "SELECT * FROM db.t WHERE (time >= 1000 AND time <= 2000)",
		},
		{
			name: "timeFrom and timeTo",
			in:   "SELECT * FROM db.t WHERE time >= $__timeFrom AND time <= $__timeTo",
			want: "SELECT * FROM db.t WHERE time >= 1000 AND time <= 2000",
		},
		{
			name: "no macros is unchanged",
			in:   "SELECT * FROM db.t",
			want: "SELECT * FROM db.t",
		},
		{
			name: "function form timeFrom and timeTo",
			in:   "SELECT * FROM db.t WHERE time >= $__timeFrom() AND time <= $__timeTo( )",
			want: "SELECT * FROM db.t WHERE time >= 1000 AND time <= 2000",
		},
		{
			name: "timeFilter with one nested paren level",
			in:   "SELECT * FROM db.t WHERE $__timeFilter(cast(x))",
			want: "SELECT * FROM db.t WHERE (cast(x) >= 1000 AND cast(x) <= 2000)",
		},
		{
			name: "longer identifier is not mangled",
			in:   "SELECT $__timeFromage FROM db.t",
			want: "SELECT $__timeFromage FROM db.t",
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

func TestParseTableQueryResponseError(t *testing.T) {
	body := []byte(`{"code":500,"message":"boom"}`)
	ds, err := parseTableQueryResponse(body)
	if err != nil {
		t.Fatalf("unexpected parse error: %v", err)
	}
	if ds.Code != 500 || ds.Message != "boom" {
		t.Fatalf("error status not parsed: code=%d message=%q", ds.Code, ds.Message)
	}
}

func TestParseTableQueryResponseInvalidJSON(t *testing.T) {
	if _, err := parseTableQueryResponse([]byte("not json")); err == nil {
		t.Fatalf("expected an error for malformed JSON")
	}
}

// TestBuildTableFrameRowMajor pins the response orientation: the table endpoint
// serialises values ROW-major (values[row][col]), so a field must gather a
// single column across every row. The two-row fixture below mirrors the shape
// asserted by IoTDB's own IoTDBRestServiceIT.testQuery and would fail if the
// values were read column-major.
func TestBuildTableFrameRowMajor(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "i", "d", "b", "s"},
		DataTypes:   []string{"TIMESTAMP", "INT64", "DOUBLE", "BOOLEAN", "TEXT"},
		Values: [][]interface{}{
			{float64(1600000000000), float64(42), float64(3.5), true, "hello"},  // row 0
			{float64(1600000001000), float64(43), float64(4.5), false, "world"}, // row 1
		},
	}

	frame := buildTableFrame(ds, msNs)
	if len(frame.Fields) != 5 {
		t.Fatalf("expected 5 fields, got %d", len(frame.Fields))
	}
	for _, f := range frame.Fields {
		if f.Len() != 2 {
			t.Fatalf("field %q has len %d, want 2 (row count)", f.Name, f.Len())
		}
	}

	// time column across both rows -> *time.Time
	if v, ok := frame.Fields[0].At(0).(*time.Time); !ok || v == nil ||
		!v.Equal(time.Unix(0, 1600000000000*int64(time.Millisecond))) {
		t.Fatalf("time[0] = %#v", frame.Fields[0].At(0))
	}
	if v, ok := frame.Fields[0].At(1).(*time.Time); !ok || v == nil ||
		!v.Equal(time.Unix(0, 1600000001000*int64(time.Millisecond))) {
		t.Fatalf("time[1] = %#v", frame.Fields[0].At(1))
	}
	// int column
	if v := frame.Fields[1].At(0).(*int64); *v != 42 {
		t.Fatalf("i[0] = %d, want 42", *v)
	}
	if v := frame.Fields[1].At(1).(*int64); *v != 43 {
		t.Fatalf("i[1] = %d, want 43", *v)
	}
	// double column
	if v := frame.Fields[2].At(1).(*float64); *v != 4.5 {
		t.Fatalf("d[1] = %v, want 4.5", *v)
	}
	// boolean column
	if v := frame.Fields[3].At(1).(*bool); *v != false {
		t.Fatalf("b[1] = %v, want false", *v)
	}
	// text column
	if v := frame.Fields[4].At(1).(*string); *v != "world" {
		t.Fatalf("s[1] = %q, want world", *v)
	}
}

// TestParseAndBuildPreservesInt64Precision decodes a real JSON body end-to-end
// and checks that an INT64 above 2^53 is not corrupted (which a plain float64
// decode would do). 9007199254740993 == 2^53 + 1 is not representable in
// float64, so this fails unless the decoder preserves integer precision.
func TestParseAndBuildPreservesInt64Precision(t *testing.T) {
	body := []byte(`{"column_names":["v"],"data_types":["INT64"],"values":[[9007199254740993]]}`)

	ds, err := parseTableQueryResponse(body)
	if err != nil {
		t.Fatalf("unexpected parse error: %v", err)
	}
	frame := buildTableFrame(ds, msNs)
	if len(frame.Fields) != 1 || frame.Fields[0].Len() != 1 {
		t.Fatalf("unexpected frame shape: %d fields", len(frame.Fields))
	}
	got := frame.Fields[0].At(0).(*int64)
	if got == nil || *got != 9007199254740993 {
		t.Fatalf("int64 precision lost: got %v, want 9007199254740993", got)
	}
}

func TestBuildTableFieldNullsBecomeNilPointers(t *testing.T) {
	f := buildTableField("i", "INT64", []interface{}{float64(1), nil, float64(3)}, msNs)
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
	f := buildTableField("x", "SOMETHING_NEW", []interface{}{float64(7), "raw", nil}, msNs)
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
// header (a missing trailing cell) does not panic and yields equal-length,
// null-padded fields.
func TestBuildTableFrameRaggedRowIsSafe(t *testing.T) {
	ds := &tableQueryDataSet{
		ColumnNames: []string{"time", "s0"},
		DataTypes:   []string{"TIMESTAMP", "TEXT"},
		Values: [][]interface{}{
			{float64(1), "a"}, // full row
			{float64(2)},      // ragged: missing s0
		},
	}
	frame := buildTableFrame(ds, msNs)
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

// TestTimestampUnits pins the precision option's conversion factors: the
// panel's ms range must be scaled INTO server units for the macros, and raw
// TIMESTAMP ticks must be scaled into nanoseconds for rendering.
func TestTimestampUnits(t *testing.T) {
	cases := []struct {
		precision  string
		unitsPerMs int64
		nsPerUnit  int64
	}{
		{"", 1, int64(time.Millisecond)},
		{"ms", 1, int64(time.Millisecond)},
		{"us", 1000, int64(time.Microsecond)},
		{"ns", 1000000, 1},
		{" NS ", 1000000, 1},
		{"garbage", 1, int64(time.Millisecond)},
	}
	for _, c := range cases {
		unitsPerMs, nsPerUnit := timestampUnits(c.precision)
		if unitsPerMs != c.unitsPerMs || nsPerUnit != c.nsPerUnit {
			t.Fatalf("timestampUnits(%q) = (%d, %d), want (%d, %d)",
				c.precision, unitsPerMs, nsPerUnit, c.unitsPerMs, c.nsPerUnit)
		}
	}
}

// TestBuildTableFieldTimestampPrecision renders the same instant sent by a
// us- and an ns-precision server. With the old hard-coded ms conversion the
// microsecond value would land in January 1970, off by 1000x.
func TestBuildTableFieldTimestampPrecision(t *testing.T) {
	want := time.Unix(0, 1600000000000*int64(time.Millisecond)) // 2020-09-13T12:26:40Z

	_, usNs := timestampUnits("us")
	f := buildTableField("time", "TIMESTAMP", []interface{}{float64(1600000000000000)}, usNs)
	if v, ok := f.At(0).(*time.Time); !ok || v == nil || !v.Equal(want) {
		t.Fatalf("us tick rendered as %#v, want %v", f.At(0), want)
	}

	_, nsNs := timestampUnits("ns")
	f = buildTableField("time", "TIMESTAMP", []interface{}{float64(1600000000000000000)}, nsNs)
	if v, ok := f.At(0).(*time.Time); !ok || v == nil || !v.Equal(want) {
		t.Fatalf("ns tick rendered as %#v, want %v", f.At(0), want)
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
			{float64(2000), "d2", float64(22.5)},
			{float64(1000), "d1", float64(11.0)},
			{float64(1000), "d2", float64(21.0)},
			{float64(2000), "d1", float64(12.0)},
		},
	}

	frame := buildTableResponseFrame(ds, "", msNs)
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
			{float64(2000), "d2", float64(22.5)},
			{float64(1000), "d1", float64(11.0)},
		},
	}

	frame := buildTableResponseFrame(ds, tableFormatTable, msNs)
	if len(frame.Fields) != 3 {
		t.Fatalf("expected 3 plain fields, got %d", len(frame.Fields))
	}
	first, ok := frame.Fields[0].At(0).(*time.Time)
	if !ok || first == nil || !first.Equal(time.Unix(0, 2000*msNs)) {
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
			{float64(1000), "d1", float64(11.0)},
			{nil, "d2", float64(21.0)},
		},
	}

	frame := buildTableResponseFrame(ds, tableFormatTimeSeries, msNs)
	if len(frame.Fields) != 3 {
		t.Fatalf("expected plain 3-field fallback frame, got %d fields", len(frame.Fields))
	}
	if frame.TimeSeriesSchema().Type != data.TimeSeriesTypeLong {
		t.Fatalf("fallback frame should still be the long-shaped original")
	}
}
