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
	"encoding/json"
	"errors"
	"io"
	"io/ioutil"
	"net/http"
	"strings"

	"github.com/grafana/grafana-plugin-sdk-go/backend"
	"github.com/grafana/grafana-plugin-sdk-go/backend/log"
	"github.com/grafana/grafana-plugin-sdk-go/backend/resource/httpadapter"
)

// tableVariablePrefix marks a template-variable query as a table-model query.
// A variable query of the form "table:<database>:<SQL>" is executed through the
// IoTDB table-model RPC client instead of the legacy tree-model REST endpoint.
const tableVariablePrefix = "table:"

// iotdbResourceHandler wires the plugin resource endpoints. It is a method so
// the table-model variable path can reach the datasource's native-client
// session pool.
func (d *IoTDBDataSource) iotdbResourceHandler(authorization string, httpClient *http.Client) backend.CallResourceHandler {
	mux := http.NewServeMux()

	mux.Handle("/getVariables", d.getVariables(authorization, httpClient))
	mux.Handle("/getNodes", d.getNodes(authorization, httpClient))

	return httpadapter.New(mux)
}

type queryReq struct {
	Sql string `json:"sql"`
}
type nodeReq struct {
	Data []string `json:"data"`
}

type queryResp struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (d *IoTDBDataSource) getVariables(authorization string, httpClient *http.Client) http.Handler {
	fn := func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.NotFound(w, r)
			return
		}
		var sql = r.FormValue("sql")

		// table:<database>:<SQL> variables run through the table-model RPC
		// client; every other query keeps the legacy tree-model behavior.
		if strings.HasPrefix(strings.TrimSpace(sql), tableVariablePrefix) {
			d.handleTableVariableQuery(w, r, sql)
			return
		}

		var queryReq = &queryReq{Sql: sql}
		qpJson, _ := json.Marshal(queryReq)
		reader := bytes.NewReader(qpJson)
		client := &http.Client{}
		// The tree-model endpoint is always the datasource's configured URL
		// (d.Ulr), never the client-supplied "url" query parameter, so a caller
		// cannot redirect this request to an arbitrary host (SSRF).
		request, err := http.NewRequest(http.MethodPost, DataSourceUrlHandler(d.Ulr)+"/grafana/v1/variable", reader)
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, err.Error())
			return
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Add("Authorization", authorization)
		rsp, err := client.Do(request)
		if err != nil {
			log.DefaultLogger.Error("Data source is not working properly", err)
			writeJSONError(w, http.StatusInternalServerError, err.Error())
			return
		}
		body, err := io.ReadAll(rsp.Body)
		if err != nil {
			log.DefaultLogger.Error("Data source is not working properly", err)
		}

		var dataResp []string
		err = json.Unmarshal(body, &dataResp)
		if err != nil {
			log.DefaultLogger.Error("Parsing JSON error", err)
			var resultResp queryResp
			json.Unmarshal(body, &resultResp)
			defer rsp.Body.Close()
			j, err := json.Marshal(resultResp)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			_, err = w.Write(j)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}

		} else {
			defer rsp.Body.Close()
			j, err := json.Marshal(dataResp)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			_, err = w.Write(j)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}

	}
	return http.HandlerFunc(fn)
}

// handleTableVariableQuery runs a table-model variable query and writes the
// single-column result as a JSON string array (the shape Grafana's variable
// dropdown expects), or a JSON error when parsing or execution fails.
func (d *IoTDBDataSource) handleTableVariableQuery(w http.ResponseWriter, r *http.Request, query string) {
	database, sql, err := parseTableVariableQuery(query)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, err.Error())
		return
	}
	sql = d.expandVariableMacros(sql)
	values, err := d.tableVariableValues(r.Context(), database, sql)
	if err != nil {
		log.DefaultLogger.Error("table-model variable query failed", "err", err)
		writeJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, values)
}

// parseTableVariableQuery splits a table-model variable query of the form
// "table:<database>:<SQL>" into its database and SQL parts. Both parts are
// required; an empty or missing part is an error rather than a fallback to the
// legacy path.
func parseTableVariableQuery(query string) (database, sql string, err error) {
	body := strings.TrimSpace(query)
	if !strings.HasPrefix(body, tableVariablePrefix) {
		return "", "", errors.New("table-model variable query must start with table:")
	}
	rest := body[len(tableVariablePrefix):]
	separator := strings.IndexByte(rest, ':')
	if separator < 0 {
		return "", "", errors.New("table-model variable query is missing the database:SQL separator")
	}
	database = strings.TrimSpace(rest[:separator])
	sql = strings.TrimSpace(rest[separator+1:])
	if database == "" {
		return "", "", errors.New("table-model variable query requires a database")
	}
	if sql == "" {
		return "", "", errors.New("table-model variable query requires SQL")
	}
	return database, sql, nil
}

// writeJSON writes a value as a JSON response with the default 200 status.
func writeJSON(w http.ResponseWriter, value interface{}) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}

// writeJSONError writes a machine-readable JSON error with an explicit HTTP
// status so a failed variable query is never mistaken for an empty result.
func writeJSONError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(queryResp{Code: status, Message: message})
}

func (d *IoTDBDataSource) getNodes(authorization string, client *http.Client) http.Handler {
	fn := func(w http.ResponseWriter, r *http.Request) {
		s, _ := ioutil.ReadAll(r.Body)
		if r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		var nodeReq nodeReq
		err := json.Unmarshal(s, &nodeReq)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		qpJson, _ := json.Marshal(nodeReq.Data)
		reader := bytes.NewReader(qpJson)

		// The node endpoint is always the datasource's configured URL (d.Ulr),
		// never a client-supplied URL, so a caller cannot redirect this request
		// to an arbitrary host (SSRF).
		request, err := http.NewRequest(http.MethodPost, DataSourceUrlHandler(d.Ulr)+"/grafana/v1/node", reader)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Add("Authorization", authorization)
		rsp, err := client.Do(request)
		if err != nil {
			log.DefaultLogger.Error("Data source is not working properly", err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		body, err := io.ReadAll(rsp.Body)
		if err != nil {
			log.DefaultLogger.Error("Data source is not working properly", err)
		}

		var dataResp []string
		err = json.Unmarshal(body, &dataResp)
		if err != nil {
			log.DefaultLogger.Error("Parsing JSON error", err)
			var resultResp queryResp
			json.Unmarshal(body, &resultResp)
			defer rsp.Body.Close()
			j, err := json.Marshal(resultResp)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			_, err = w.Write(j)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}

		} else {
			defer rsp.Body.Close()
			j, err := json.Marshal(dataResp)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			_, err = w.Write(j)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}

	}
	return http.HandlerFunc(fn)
}
