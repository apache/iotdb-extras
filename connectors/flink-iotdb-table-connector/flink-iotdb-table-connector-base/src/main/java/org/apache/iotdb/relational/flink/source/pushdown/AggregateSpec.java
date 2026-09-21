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

package org.apache.iotdb.relational.flink.source.pushdown;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serializable description of a pushed-down aggregate query.
 *
 * <p>{@code selectExpressions} are the rendered grouping keys followed by the aggregate
 * expressions; {@code groupByExpressions} are the rendered grouping keys.
 */
public class AggregateSpec implements Serializable {

  private static final long serialVersionUID = 1L;

  private final List<String> selectExpressions;
  private final List<String> groupByExpressions;

  public AggregateSpec(List<String> selectExpressions, List<String> groupByExpressions) {
    this.selectExpressions = Collections.unmodifiableList(new ArrayList<>(selectExpressions));
    this.groupByExpressions = Collections.unmodifiableList(new ArrayList<>(groupByExpressions));
  }

  public List<String> getSelectExpressions() {
    return selectExpressions;
  }

  public List<String> getGroupByExpressions() {
    return groupByExpressions;
  }
}
