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

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activation guard for the derived-latest DAO. It mirrors {@link IoTDBTableRawOnlyEnabledCondition}
 * (selector {@code database.ts.type=iotdb-table} plus the explicit {@code
 * iotdb.ts.experimental-raw-only=true} opt-in) AND additionally requires {@code
 * database.ts_latest.type=iotdb-table}.
 *
 * <p>Requiring the timeseries selector as well as the latest selector is deliberate: the latest
 * value is derived from the {@code telemetry} table that only the IoTDB writer populates. Were the
 * DAO to activate on {@code database.ts_latest.type=iotdb-table} alone, a deployment that left
 * {@code database.ts.type} on another backend would read latest values from a table this module
 * never writes. Coupling both selectors keeps the latest path coherent with the write path and
 * leaves the module fully inert on any deployment that has not opted into the IoTDB Table Mode
 * timeseries backend.
 */
final class IoTDBTableLatestEnabledCondition implements Condition {

  private static final String TS_SELECTOR_PROPERTY = "database.ts.type";
  private static final String TS_LATEST_SELECTOR_PROPERTY = "database.ts_latest.type";
  private static final String SELECTOR_VALUE = "iotdb-table";
  private static final String EXPERIMENTAL_PROPERTY = "iotdb.ts.experimental-raw-only";

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String ts = context.getEnvironment().getProperty(TS_SELECTOR_PROPERTY);
    String tsLatest = context.getEnvironment().getProperty(TS_LATEST_SELECTOR_PROPERTY);
    boolean tsSelected = ts != null && SELECTOR_VALUE.equalsIgnoreCase(ts.trim());
    boolean tsLatestSelected = tsLatest != null && SELECTOR_VALUE.equalsIgnoreCase(tsLatest.trim());
    boolean experimental =
        context.getEnvironment().getProperty(EXPERIMENTAL_PROPERTY, Boolean.class, false);
    return tsSelected && tsLatestSelected && experimental;
  }
}
