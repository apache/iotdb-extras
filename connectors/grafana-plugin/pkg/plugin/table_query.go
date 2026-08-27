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
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/apache/iotdb-client-go/v2/client"
	"github.com/grafana/grafana-plugin-sdk-go/backend"
	"github.com/grafana/grafana-plugin-sdk-go/backend/log"
	"github.com/grafana/grafana-plugin-sdk-go/data"
)

// TableModelSqlType is the QueryEditor mode that runs standard table-model SQL
// through the native Go client (apache/iotdb-client-go), as opposed to the
// tree-model modes ("SQL: Full Customized" / "SQL: Drop-down List") that build
// root.* paths and go through the REST /grafana endpoints.
const TableModelSqlType = "SQL: Table Model"

// Result formats for the table-model mode. Time series (the default) sorts
// rows ascending by the first TIMESTAMP column and pivots a long-shaped result
// (time + tag columns + value columns) into one labeled series per tag
// combination; Table returns the rows exactly as the server sent them.
const (
	tableFormatTimeSeries = "Time series"
	tableFormatTable      = "Table"
)

// Connection settings for the native client. The RPC endpoint is the
// datasource's "rpc address" option, or the URL's host with the default RPC
// port when unset. The pool is created lazily on the first table-model query
// so datasources that only use the tree model never open an RPC connection.
const (
	defaultRPCPort            = "6667"
	tablePoolMaxSize          = 8
	tableConnectTimeoutMs     = 10000
	tableWaitSessionTimeoutMs = 60000
	defaultQueryTimeoutMs     = int64(60000)
)

// timeFilterRe matches Grafana's $__timeFilter(column) macro; the column is
// optional and defaults to "time". One level of nested parentheses is allowed
// so expressions like $__timeFilter(cast(x)) survive intact.
var timeFilterRe = regexp.MustCompile(`\$__timeFilter\(\s*((?:[^()]|\([^()]*\))*?)\s*\)`)

// timeFromRe / timeToRe match $__timeFrom / $__timeTo, with or without the
// trailing () that Grafana's SQL data sources use ($__timeFrom()).
var (
	timeFromRe = regexp.MustCompile(`\$__timeFrom\b(?:\s*\(\s*\))?`)
	timeToRe   = regexp.MustCompile(`\$__timeTo\b(?:\s*\(\s*\))?`)
	// $__evaluationTime is an explicit opt-in for SQL constructs such as HOP
	// ORIGIN that need Grafana's point-in-time evaluation bound. It is deliberately
	// separate from $__timeFrom/__timeTo so Instant does not alter ordinary range
	// predicates or user-authored constants.
	evaluationTimeRe = regexp.MustCompile(`\$__evaluationTime\b(?:\s*\(\s*\))?`)
	// These patterns intentionally match the macro prefix. hasStandaloneMacro
	// and replaceStandaloneMacro reject an identifier byte after the match, so
	// $__interval_ms is not mistaken for $__interval.
	intervalRe   = regexp.MustCompile(`\$__interval`)
	intervalMSRe = regexp.MustCompile(`\$__interval_ms`)
)

// activeFromRe matches $__activeFrom, the lower bound of the node-liveness
// window used by table-model template-variable queries. It is a variable-path
// macro (not a panel-query macro) and is expanded by expandVariableMacros.
var activeFromRe = regexp.MustCompile(`\$__activeFrom\b`)

const invalidIntervalMacroMessage = "Grafana query interval must be positive when $__interval or $__interval_ms is used"

// nodeActiveTTL is the template-variable liveness window. A node is considered
// active only if its most recent sample falls within the last nodeActiveTTL.
// The bridge writes samples with their Prometheus scrape timestamp, so this is
// "the node produced a scrape within the last nodeActiveTTL"; a node that stops
// producing samples ages out of the window and disappears from the variable.
const nodeActiveTTL = 5 * time.Minute

// formatTimeLiteral renders a panel-range bound as an ISO 8601 UTC timestamp
// literal (e.g. 2020-09-13T12:26:40.000+00:00). The server parses such a
// literal in its own configured timestamp precision, so the expansion works
// unchanged on ms, us and ns servers — unlike a bare epoch integer, which the
// server would interpret in raw server units.
func formatTimeLiteral(ms int64) string {
	return time.UnixMilli(ms).UTC().Format("2006-01-02T15:04:05.000") + "+00:00"
}

// currentTime returns the injectable clock, or the real wall clock when no
// clock is configured.
func (d *IoTDBDataSource) currentTime() time.Time {
	if d.now != nil {
		return d.now()
	}
	return time.Now()
}

// expandVariableMacros rewrites the template-variable-only macros before a
// table-model variable query runs. $__activeFrom becomes the "now - TTL"
// instant rendered as an ISO 8601 UTC timestamp literal — the same literal form
// panel queries receive for $__timeFrom — so a variable query can restrict its
// result to recently active nodes without the server evaluating a time
// function of its own.
func (d *IoTDBDataSource) expandVariableMacros(sql string) string {
	from := d.currentTime().Add(-nodeActiveTTL).UnixMilli()
	return activeFromRe.ReplaceAllString(sql, formatTimeLiteral(from))
}

// expandTableMacros rewrites the Grafana time and interval macros a dashboard
// author can put in table-model SQL.
//
//	$__timeFilter(col)      -> (col >= <from> AND col <= <to>)
//	$__timeFrom[()]         -> <from>
//	$__timeTo[()]           -> <to>
//	$__evaluationTime[()]   -> <to>
//	$__interval              -> a fixed-width IoTDB duration literal
//	$__interval_ms           -> the interval in milliseconds, per Grafana's contract
//
// Bounds are ISO 8601 UTC timestamp literals, which IoTDB compares against
// TIMESTAMP columns independently of the server's timestamp precision.
func expandTableMacros(sql string, startMs int64, endMs int64, intervalMS int64) (string, error) {
	hasInterval := hasStandaloneMacro(sql, intervalRe)
	hasIntervalMS := hasStandaloneMacro(sql, intervalMSRe)
	if (hasInterval || hasIntervalMS) && intervalMS <= 0 {
		// Defensive validation for direct callers. queryTableModel performs the
		// authoritative request-path check before acquiring an RPC session.
		return "", errors.New(invalidIntervalMacroMessage)
	}

	if hasIntervalMS {
		sql = replaceStandaloneMacro(sql, intervalMSRe, strconv.FormatInt(intervalMS, 10))
	}
	if hasInterval {
		duration, err := formatIoTDBDuration(intervalMS)
		if err != nil {
			return "", err
		}
		sql = replaceStandaloneMacro(sql, intervalRe, duration)
	}

	from := formatTimeLiteral(startMs)
	to := formatTimeLiteral(endMs)
	sql = replaceStandaloneMacro(sql, evaluationTimeRe, to)
	sql = timeFilterRe.ReplaceAllStringFunc(sql, func(m string) string {
		col := strings.TrimSpace(timeFilterRe.FindStringSubmatch(m)[1])
		if col == "" {
			col = "time"
		}
		return "(" + col + " >= " + from + " AND " + col + " <= " + to + ")"
	})
	sql = timeFromRe.ReplaceAllString(sql, from)
	sql = timeToRe.ReplaceAllString(sql, to)
	return sql, nil
}

// expandInstantTableMacros expands an Instant query over the complete Grafana
// range, while exposing the range end through the explicit $__evaluationTime
// macro. The result selector later chooses the latest row at or before that
// evaluation time for each series.
func expandInstantTableMacros(sql string, startMs int64, endMs int64, intervalMS int64) (string, error) {
	return expandTableMacros(sql, startMs, endMs, intervalMS)
}

// hasStandaloneMacro reports whether re has a match that is not followed by an
// identifier character. Go's regexp package intentionally has no lookahead,
// so the boundary check is performed while scanning matches.
func hasStandaloneMacro(sql string, re *regexp.Regexp) bool {
	for _, match := range re.FindAllStringIndex(sql, -1) {
		if match[1] == len(sql) || !isSQLIdentifierByte(sql[match[1]]) {
			return true
		}
	}
	return false
}

func replaceStandaloneMacro(sql string, re *regexp.Regexp, replacement string) string {
	matches := re.FindAllStringIndex(sql, -1)
	if len(matches) == 0 {
		return sql
	}
	var b strings.Builder
	last := 0
	for _, match := range matches {
		if match[1] < len(sql) && isSQLIdentifierByte(sql[match[1]]) {
			continue
		}
		b.WriteString(sql[last:match[0]])
		b.WriteString(replacement)
		last = match[1]
	}
	b.WriteString(sql[last:])
	return b.String()
}

func isSQLIdentifierByte(b byte) bool {
	return b == '_' || b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
}

// formatIoTDBDuration uses only fixed-width units accepted by IoTDB and
// avoids calendar month/year semantics. The largest exact unit is selected.
func formatIoTDBDuration(intervalMS int64) (string, error) {
	if intervalMS <= 0 {
		return "", errors.New("Grafana query interval must be positive")
	}
	units := []struct {
		milliseconds int64
		suffix       string
	}{
		{7 * 24 * 60 * 60 * 1000, "w"},
		{24 * 60 * 60 * 1000, "d"},
		{60 * 60 * 1000, "h"},
		{60 * 1000, "m"},
		{1000, "s"},
		{1, "ms"},
	}
	for _, unit := range units {
		if intervalMS%unit.milliseconds == 0 {
			return strconv.FormatInt(intervalMS/unit.milliseconds, 10) + unit.suffix, nil
		}
	}
	return "", errors.New("cannot format Grafana query interval")
}

// quoteTableIdentifier wraps a table-model identifier in double quotes
// (doubling any embedded quote), the relational grammar's quoted-identifier
// form, so a database name survives the USE statement verbatim.
func quoteTableIdentifier(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}

// tableQueryDataSet is the plugin-internal carrier for a fetched table-model
// result: column names and IoTDB type names as reported by the client, plus
// row-major cell values (values[row][col]) holding the client's native Go
// representations (time.Time for TIMESTAMP/DATE, string for TEXT/STRING,
// []byte for BLOB, bool/int32/int64/float32/float64 for the scalars, nil for
// null).
type tableQueryDataSet struct {
	ColumnNames []string
	DataTypes   []string
	Values      [][]interface{}
}

// tableResultSet is the slice of the native client's SessionDataSet the fetch
// path needs; narrowing it to an interface keeps fetchTableDataSet testable
// without a live server. Column indexes are 1-based, matching the client.
type tableResultSet interface {
	Next() (bool, error)
	GetColumnNames() []string
	GetColumnTypes() []string
	GetObjectByIndex(columnIndex int32) (interface{}, error)
	Close() error
}

// fetchTableDataSet drains a result set into a tableQueryDataSet. The caller
// owns closing the result set.
func fetchTableDataSet(rs tableResultSet) (*tableQueryDataSet, error) {
	names := rs.GetColumnNames()
	types := rs.GetColumnTypes()
	dataSet := &tableQueryDataSet{ColumnNames: names, DataTypes: types, Values: [][]interface{}{}}
	for {
		hasNext, err := rs.Next()
		if err != nil {
			return nil, err
		}
		if !hasNext {
			return dataSet, nil
		}
		row := make([]interface{}, len(names))
		for i := range names {
			value, err := rs.GetObjectByIndex(int32(i) + 1)
			if err != nil {
				return nil, err
			}
			row[i] = value
		}
		dataSet.Values = append(dataSet.Values, row)
	}
}

// tableRPCEndpoint resolves the host and port of the IoTDB RPC service the
// native client connects to: the datasource's "rpc address" option when set
// (host or host:port), otherwise the datasource URL's host with the default
// RPC port.
func (d *IoTDBDataSource) tableRPCEndpoint() (string, string, error) {
	if addr := strings.TrimSpace(d.RPCAddress); addr != "" {
		if host, port, err := net.SplitHostPort(addr); err == nil {
			return host, port, nil
		}
		return strings.Trim(addr, "[]"), defaultRPCPort, nil
	}
	raw := strings.TrimSpace(d.Ulr)
	if u, err := url.Parse(raw); err == nil && u.Hostname() != "" {
		return u.Hostname(), defaultRPCPort, nil
	}
	// A bare host[:restPort] without a scheme parses as opaque; retry with one.
	if u, err := url.Parse("http://" + raw); err == nil && u.Hostname() != "" {
		return u.Hostname(), defaultRPCPort, nil
	}
	return "", "", errors.New("cannot derive the IoTDB RPC host from the datasource URL; please set the rpc address option")
}

// getTablePool lazily creates the shared native-client session pool for this
// datasource instance. Pool construction does not connect; connection errors
// surface on GetSession.
func (d *IoTDBDataSource) getTablePool() (*client.TableSessionPool, error) {
	d.tablePoolMu.Lock()
	defer d.tablePoolMu.Unlock()
	if d.tablePool != nil {
		return d.tablePool, nil
	}
	host, port, err := d.tableRPCEndpoint()
	if err != nil {
		return nil, err
	}
	poolConfig := &client.PoolConfig{
		Host:     host,
		Port:     port,
		UserName: d.Username,
		Password: d.password,
	}
	pool := client.NewTableSessionPool(poolConfig, tablePoolMaxSize, tableConnectTimeoutMs, tableWaitSessionTimeoutMs, false)
	d.tablePool = &pool
	return d.tablePool, nil
}

// queryTableModel runs a table-model SQL query through the native client and
// turns the result set into a Grafana data frame.
func (d *IoTDBDataSource) queryTableModel(ctx context.Context, qp *queryParam) backend.DataResponse {
	wantsInstant := qp.Instant
	wantsRange := !qp.Instant || qp.Range
	if wantsInstant && wantsRange {
		response := backend.DataResponse{}
		rangeQuery := *qp
		rangeQuery.Instant = false
		rangeResponse := d.queryTableModelOnce(ctx, &rangeQuery, "response_range")
		response.Frames = append(response.Frames, rangeResponse.Frames...)
		if rangeResponse.Error != nil {
			response.Error = rangeResponse.Error
			return response
		}

		instantQuery := *qp
		instantResponse := d.queryTableModelOnce(ctx, &instantQuery, "response_instant")
		response.Frames = append(response.Frames, instantResponse.Frames...)
		response.Error = instantResponse.Error
		return response
	}

	return d.queryTableModelOnce(ctx, qp, "response")
}

func (d *IoTDBDataSource) queryTableModelOnce(ctx context.Context, qp *queryParam, frameName string) backend.DataResponse {
	response := backend.DataResponse{}

	if (hasStandaloneMacro(qp.Sql, intervalRe) || hasStandaloneMacro(qp.Sql, intervalMSRe)) && qp.IntervalMS <= 0 {
		// This is the authoritative guard: reject invalid Grafana input before
		// getTablePool can create or acquire an RPC session.
		response.Error = errors.New(invalidIntervalMacroMessage)
		return response
	}

	runner := d.tableQueryRunner
	if runner == nil {
		runner = d.executeTableQuery
	}
	dataSet, err := runner(ctx, qp)
	if err != nil {
		response.Error = err
		return response
	}
	if qp.Instant {
		dataSet = prepareInstantDataSet(dataSet, qp.EndTime)
	}

	if !strings.EqualFold(qp.Format, tableFormatTable) && !hasPlottableValue(dataSet) {
		// Time Series with no plottable values — zero rows, or rows whose value
		// columns are all NULL (a HOP/rate query over sparse data returns NULL
		// windows) — has no frame so Grafana shows "No data" instead of bare
		// axes. Table format keeps the empty frame so column headers stay visible.
		return response
	}

	frame := buildTableResponseFrame(dataSet, qp.Format, qp.LegendFormat)
	frame.Name = frameName
	response.Frames = append(response.Frames, frame)
	return response
}

// executeTableQuery runs and fetches one table-model query. queryTableModel
// owns response semantics so the same zero-row path is covered in tests.
func (d *IoTDBDataSource) executeTableQuery(ctx context.Context, qp *queryParam) (*tableQueryDataSet, error) {
	var sql string
	var err error
	if qp.Instant {
		sql, err = expandInstantTableMacros(qp.Sql, qp.StartTime, qp.EndTime, qp.IntervalMS)
	} else {
		sql, err = expandTableMacros(qp.Sql, qp.StartTime, qp.EndTime, qp.IntervalMS)
	}
	if err != nil {
		return nil, err
	}
	return d.executeTableStatement(ctx, qp.Database, sql)
}

// prepareInstantDataSet enforces the point-in-time response contract after the
// SQL has evaluated over the requested range. For each label/series identity it
// keeps the latest row whose timestamp is at or before evaluationMs, then
// normalizes that row's timestamp to evaluationMs. Scalar results without a
// TIMESTAMP column receive a synthetic evaluation-time column.
func prepareInstantDataSet(dataSet *tableQueryDataSet, evaluationMs int64) *tableQueryDataSet {
	evaluation := time.UnixMilli(evaluationMs).UTC()
	timeCol := -1
	for i, dataType := range dataSet.DataTypes {
		if strings.EqualFold(dataType, "TIMESTAMP") {
			timeCol = i
			break
		}
	}

	prepared := &tableQueryDataSet{
		ColumnNames: append([]string(nil), dataSet.ColumnNames...),
		DataTypes:   append([]string(nil), dataSet.DataTypes...),
		Values:      make([][]interface{}, 0, len(dataSet.Values)),
	}
	if timeCol < 0 {
		seenSeries := make(map[string]struct{}, len(dataSet.Values))
		prepared.ColumnNames = append([]string{"time"}, prepared.ColumnNames...)
		prepared.DataTypes = append([]string{"TIMESTAMP"}, prepared.DataTypes...)
		for _, row := range dataSet.Values {
			seriesKey := instantSeriesKey(dataSet, row, timeCol)
			if _, exists := seenSeries[seriesKey]; exists {
				continue
			}
			seenSeries[seriesKey] = struct{}{}
			instantRow := make([]interface{}, 0, len(row)+1)
			instantRow = append(instantRow, evaluation)
			instantRow = append(instantRow, row...)
			prepared.Values = append(prepared.Values, instantRow)
		}
		return prepared
	}

	// Keep the first-seen series order while replacing each series' candidate
	// whenever a later eligible timestamp is encountered. A row after the
	// evaluation time can never satisfy Instant semantics.
	type candidate struct {
		row  []interface{}
		time time.Time
	}
	candidates := make(map[string]candidate, len(dataSet.Values))
	seriesOrder := make([]string, 0, len(dataSet.Values))
	for _, row := range dataSet.Values {
		rowTime, ok := rowTimeAt(row, timeCol)
		if !ok || rowTime.After(evaluation) {
			continue
		}
		seriesKey := instantSeriesKey(dataSet, row, timeCol)
		current, exists := candidates[seriesKey]
		if !exists {
			seriesOrder = append(seriesOrder, seriesKey)
		} else if !rowTime.After(current.time) {
			// Preserve the first row when timestamps tie.
			continue
		}
		candidates[seriesKey] = candidate{row: row, time: rowTime}
	}
	for _, seriesKey := range seriesOrder {
		row := candidates[seriesKey].row
		instantRow := append([]interface{}(nil), row...)
		instantRow[timeCol] = evaluation
		prepared.Values = append(prepared.Values, instantRow)
	}
	return prepared
}

// instantSeriesKey mirrors the long-to-wide series identity: non-time,
// non-numeric columns are labels, while each numeric column is a value series.
// Grouping rows by this key lets Instant select one latest eligible point for
// each series rather than treating the query's globally last row as its result.
func instantSeriesKey(dataSet *tableQueryDataSet, row []interface{}, timeCol int) string {
	var key strings.Builder
	for col, cell := range row {
		if col == timeCol || col < len(dataSet.DataTypes) && isNumericTableType(dataSet.DataTypes[col]) {
			continue
		}
		value := "<nil>"
		if cell != nil {
			value = fmt.Sprintf("%T:%s", cell, toString(cell))
		}
		key.WriteString(strconv.Itoa(col))
		key.WriteByte(':')
		key.WriteString(strconv.Itoa(len(value)))
		key.WriteByte(':')
		key.WriteString(value)
		key.WriteByte(';')
	}
	return key.String()
}

// executeTableStatement runs a table-model SQL statement against the given
// database on a pooled native-client session and returns the fetched dataset.
// The database is USEd on the session so a statement's table references resolve
// against it regardless of any session state left by earlier queries. When
// tableExecutor is non-nil it is used instead of the real RPC path.
func (d *IoTDBDataSource) executeTableStatement(ctx context.Context, database, sql string) (*tableQueryDataSet, error) {
	if d.tableExecutor != nil {
		return d.tableExecutor(ctx, database, sql)
	}

	pool, err := d.getTablePool()
	if err != nil {
		return nil, err
	}
	session, err := pool.GetSession()
	if err != nil {
		log.DefaultLogger.Error("Cannot connect to the IoTDB RPC service", "err", err)
		return nil, fmt.Errorf("cannot connect to the IoTDB RPC service: %w", err)
	}
	defer func() {
		if closeErr := session.Close(); closeErr != nil {
			log.DefaultLogger.Error("Failed to return session to the pool", "err", closeErr)
		}
	}()

	if db := strings.TrimSpace(database); db != "" {
		if err := session.ExecuteNonQueryStatement("USE " + quoteTableIdentifier(db)); err != nil {
			return nil, err
		}
	}

	timeout := defaultQueryTimeoutMs
	if deadline, ok := ctx.Deadline(); ok {
		if ms := time.Until(deadline).Milliseconds(); ms > 0 {
			timeout = ms
		}
	}
	resultSet, err := session.ExecuteQueryStatement(sql, &timeout)
	if err != nil {
		return nil, err
	}
	defer func() {
		if closeErr := resultSet.Close(); closeErr != nil {
			log.DefaultLogger.Error("Failed to close the result set", "err", closeErr)
		}
	}()

	return fetchTableDataSet(resultSet)
}

// tableVariableValues runs a table-model template-variable query and returns
// its single string column as an ordered, NULL-free slice for Grafana's
// variable dropdown.
func (d *IoTDBDataSource) tableVariableValues(ctx context.Context, database, sql string) ([]string, error) {
	dataSet, err := d.executeTableStatement(ctx, database, sql)
	if err != nil {
		return nil, err
	}
	return tableVariableStrings(dataSet)
}

// tableVariableStrings extracts the values of the single column a template
// variable query must project. NULL cells are skipped and non-string cells are
// rendered with the table-mode string coercion, preserving server order.
func tableVariableStrings(dataSet *tableQueryDataSet) ([]string, error) {
	if len(dataSet.ColumnNames) != 1 {
		return nil, fmt.Errorf("template variable query must project exactly one column, got %d", len(dataSet.ColumnNames))
	}
	values := make([]string, 0, len(dataSet.Values))
	for _, row := range dataSet.Values {
		if len(row) == 0 {
			continue
		}
		if row[0] == nil {
			continue
		}
		values = append(values, toString(row[0]))
	}
	return values, nil
}

// buildTableResponseFrame turns a fetched dataset into the response frame,
// honoring the query's format. In the default Time series format the rows are
// sorted ascending by the first TIMESTAMP column and a long-shaped result
// (time + string tag columns + value columns) is pivoted into one labeled
// series per tag combination, so a multi-device query renders as separate
// lines instead of one interleaved series; when the pivot does not apply (or
// fails, e.g. on a null timestamp) the plain frame is returned. The Table
// format preserves the server's row order untouched.
func buildTableResponseFrame(dataSet *tableQueryDataSet, format string, legendFormat string) *data.Frame {
	// Anything that is not explicitly the Table format gets the default
	// time-series treatment, including queries saved before FORMAT existed.
	isTimeSeries := !strings.EqualFold(format, tableFormatTable)
	if isTimeSeries {
		sortRowsByFirstTimestamp(dataSet)
	}
	frame := buildTableFrame(dataSet)
	if isTimeSeries && frame.TimeSeriesSchema().Type == data.TimeSeriesTypeLong {
		if wide, err := data.LongToWide(frame, nil); err == nil {
			frame = wide
		}
	}
	if isTimeSeries && !isNoopLegendFormat(legendFormat) {
		applyLegendFormat(frame, legendFormat)
	}
	return frame
}

// isNumericTableType reports whether a table-model result type carries a
// plottable numeric value (as opposed to a tag/string, timestamp, or blob).
func isNumericTableType(dataType string) bool {
	switch strings.ToUpper(dataType) {
	case "INT32", "INT64", "FLOAT", "DOUBLE":
		return true
	default:
		return false
	}
}

// hasPlottableValue reports whether the dataset has at least one non-null cell
// in a numeric column. Zero-row datasets and value columns that are entirely
// NULL both report false, so queryTableModel can collapse either into the
// "No data" state instead of drawing bare axes.
func hasPlottableValue(dataSet *tableQueryDataSet) bool {
	for _, row := range dataSet.Values {
		for col, cell := range row {
			if cell == nil {
				continue
			}
			if col < len(dataSet.DataTypes) && isNumericTableType(dataSet.DataTypes[col]) {
				return true
			}
		}
	}
	return false
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
		return okA && va.Before(vb)
	})
}

func rowTimeAt(row []interface{}, col int) (time.Time, bool) {
	if col >= len(row) {
		return time.Time{}, false
	}
	t, ok := row[col].(time.Time)
	return t, ok
}

// buildTableFrame converts the row-major dataset into a Grafana frame, one
// typed field per column driven by the reported data types; each field gathers
// a single column across all rows, so every field ends up the same length (the
// row count) that Grafana requires.
func buildTableFrame(dataSet *tableQueryDataSet) *data.Frame {
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
		frame.Fields = append(frame.Fields, buildTableField(name, dataType, columnValues))
	}
	return frame
}

// buildTableField turns one column's native client values into a typed,
// nullable Grafana field selected by the IoTDB data type. The client already
// converts TIMESTAMP (and DATE) values to time.Time using the server-reported
// timestamp precision, so no unit handling happens here. DATE and BLOB render
// as strings (yyyy-MM-dd and 0x-prefixed hex), matching the REST behavior the
// mode previously had.
func buildTableField(name string, dataType string, values []interface{}) *data.Field {
	switch strings.ToUpper(dataType) {
	case "TIMESTAMP":
		out := make([]*time.Time, len(values))
		for i, v := range values {
			if t, ok := v.(time.Time); ok {
				value := t
				out[i] = &value
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
	case "DATE":
		out := make([]*string, len(values))
		for i, v := range values {
			if t, ok := v.(time.Time); ok {
				value := t.Format("2006-01-02")
				out[i] = &value
			}
		}
		return data.NewField(name, nil, out)
	case "BLOB":
		out := make([]*string, len(values))
		for i, v := range values {
			if b, ok := v.([]byte); ok {
				value := "0x" + hex.EncodeToString(b)
				out[i] = &value
			}
		}
		return data.NewField(name, nil, out)
	default:
		// TEXT, STRING and anything unrecognised render as strings.
		out := make([]*string, len(values))
		for i, v := range values {
			if v == nil {
				continue
			}
			value := toString(v)
			out[i] = &value
		}
		return data.NewField(name, nil, out)
	}
}

// toInt64 coerces the client's integer representations (INT32 -> int32,
// INT64 -> int64) to int64.
func toInt64(v interface{}) (int64, bool) {
	switch n := v.(type) {
	case int64:
		return n, true
	case int32:
		return int64(n), true
	}
	return 0, false
}

// toFloat64 coerces the client's floating representations (FLOAT -> float32,
// DOUBLE -> float64) to float64.
func toFloat64(v interface{}) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case float32:
		return float64(n), true
	}
	return 0, false
}

// toString renders a value for a text column without losing it, whatever the
// client handed over.
func toString(v interface{}) string {
	switch value := v.(type) {
	case string:
		return value
	case []byte:
		return "0x" + hex.EncodeToString(value)
	case time.Time:
		return value.UTC().Format(time.RFC3339Nano)
	case bool:
		return strconv.FormatBool(value)
	case int32:
		return strconv.FormatInt(int64(value), 10)
	case int64:
		return strconv.FormatInt(value, 10)
	case float32:
		return strconv.FormatFloat(float64(value), 'f', -1, 32)
	case float64:
		return strconv.FormatFloat(value, 'f', -1, 64)
	default:
		return fmt.Sprintf("%v", v)
	}
}

// legendFormatRe matches Grafana legend format template placeholders like
// {{instance}} or {{ instance }}; whitespace inside the braces is ignored.
var legendFormatRe = regexp.MustCompile(`\{\{\s*([^{}]+?)\s*\}\}`)

var prometheusLegendLabelAliases = map[string]string{
	"nodeType":  "node_type",
	"nodeId":    "node_id",
	"name":      "label_name",
	"type":      "label_type",
	"database":  "database_name",
	"interface": "interface_name",
	"id":        "label_id",
	"rate":      "label_rate",
	"from":      "source_from",
	"index":     "label_index",
}

// autoLegendFormat is Grafana's sentinel for "automatic legend": it must never
// be applied as a literal series name, or every series would display "__auto".
const autoLegendFormat = "__auto"

// isNoopLegendFormat reports whether legendFormat should be left alone. An
// empty format and Grafana's __auto sentinel both mean "do not override series
// names", so Grafana falls back to its own automatic legend.
func isNoopLegendFormat(legendFormat string) bool {
	return legendFormat == "" || legendFormat == autoLegendFormat
}

// applyLegendFormat resolves the Grafana legendFormat template against
// each non-time field's labels and sets the field's DisplayNameFromDS
// so the series show user-friendly names instead of "value {labels...}".
func applyLegendFormat(frame *data.Frame, legendFormat string) {
	if isNoopLegendFormat(legendFormat) {
		return
	}
	for _, field := range frame.Fields {
		if field.Type().Time() {
			continue
		}
		displayName := resolveLegendFormat(legendFormat, field.Labels)
		if field.Config == nil {
			field.Config = &data.FieldConfig{}
		}
		field.Config.DisplayNameFromDS = displayName
	}
}

// resolveLegendFormat replaces {{labelName}} placeholders in format with
// the corresponding value from labels, matching Grafana's behaviour:
// optional whitespace inside the braces is ignored, known Prometheus names
// can resolve from their IoTDB storage aliases, and missing or empty values
// resolve to the label name itself, as Grafana's truthy-value check does.
func resolveLegendFormat(format string, labels data.Labels) string {
	return legendFormatRe.ReplaceAllStringFunc(format, func(match string) string {
		key := strings.TrimSpace(match[2 : len(match)-2])
		if value := labels[key]; value != "" {
			return value
		}
		if alias, ok := prometheusLegendLabelAliases[key]; ok {
			if value := labels[alias]; value != "" {
				return value
			}
		}
		return key
	})
}
