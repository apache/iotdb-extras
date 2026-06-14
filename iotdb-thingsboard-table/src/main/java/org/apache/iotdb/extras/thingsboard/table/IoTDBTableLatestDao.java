/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.extras.thingsboard.table;

import org.apache.iotdb.isession.pool.ITableSessionPool;

import lombok.extern.slf4j.Slf4j;

/**
 * Latest telemetry DAO skeleton for the IoTDB Table Mode backend.
 *
 * <p>This class is intentionally not annotated as a Spring bean. The latest-telemetry SPI binding
 * is not implemented here, so registering it now would expose a {@code
 * database.ts_latest.type=iotdb-table} selector that ThingsBoard cannot bind to a working DAO.
 *
 * <p>Strategy F keeps this class free of ThingsBoard imports and interface clauses until the
 * latest-telemetry path is implemented.
 */
@Slf4j
public class IoTDBTableLatestDao extends IoTDBTableBaseDao {
  // Not annotated @Repository yet: auto-registering it now would advertise
  // database.ts_latest.type=iotdb-table with no working DAO behind it.
  public IoTDBTableLatestDao(ITableSessionPool tableSessionPool) {
    super(tableSessionPool);
  }
}
