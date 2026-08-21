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
 * Enables the IoTDB Table Mode entity-attribute backend only when the ThingsBoard attribute
 * selector {@code database.attributes.type=iotdb-table} is set (case-insensitively, trimmed).
 *
 * <p>This selector is INDEPENDENT of {@code database.ts.type} / {@code database.ts_latest.type}:
 * the attribute DAO routes separately from the time-series DAOs (a piggy-back on the timeseries
 * selector was deliberately rejected). Upstream ThingsBoard exposes no {@code AttributesDao}
 * selector of its own, so {@code database.attributes.type} is one this module supplies. Leaving it
 * unset is the default posture: this condition returns false, the attribute bean is never
 * instantiated, and attributes keep flowing to the host entity-DB {@code AttributesDao}. The DAO is
 * inert by default and activates only when an operator opts in explicitly -- at which point {@code
 * AttributesDaoConflictGuard} withdraws ThingsBoard's own attributes bean.
 */
final class IoTDBTableAttributesEnabledCondition implements Condition {
  static final String SELECTOR_PROPERTY = "database.attributes.type";
  static final String SELECTOR_VALUE = "iotdb-table";

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String selector = context.getEnvironment().getProperty(SELECTOR_PROPERTY);
    return selector != null && SELECTOR_VALUE.equalsIgnoreCase(selector.trim());
  }
}
