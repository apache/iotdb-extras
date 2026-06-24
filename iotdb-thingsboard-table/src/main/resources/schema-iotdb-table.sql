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

CREATE DATABASE IF NOT EXISTS thingsboard;
USE thingsboard;

CREATE TABLE IF NOT EXISTS telemetry (
  entity_type STRING  TAG,    -- DEVICE, ASSET, etc.
  tenant_id   STRING  TAG,    -- multi-tenant isolation
  key         STRING  TAG,    -- telemetry key name
  entity_id   STRING  TAG,    -- ThingsBoard entity UUID
  bool_v      BOOLEAN FIELD,
  long_v      INT64   FIELD,  -- exactly one non-null per row,
  double_v    DOUBLE  FIELD,  -- mirroring AbstractTsKvEntity
  str_v       STRING  FIELD,
  json_v      TEXT    FIELD
) WITH (TTL=DEFAULT);

CREATE TABLE IF NOT EXISTS entity_attributes (
  time            TIMESTAMP TIME,
  attribute_scope STRING TAG,   -- CLIENT_SCOPE | SERVER_SCOPE | SHARED_SCOPE
  entity_type     STRING TAG,
  tenant_id       STRING TAG,
  key             STRING TAG,
  entity_id       STRING TAG,
  bool_v BOOLEAN FIELD, long_v INT64 FIELD, double_v DOUBLE FIELD,
  str_v STRING FIELD, json_v TEXT FIELD
) WITH (TTL='INF');
