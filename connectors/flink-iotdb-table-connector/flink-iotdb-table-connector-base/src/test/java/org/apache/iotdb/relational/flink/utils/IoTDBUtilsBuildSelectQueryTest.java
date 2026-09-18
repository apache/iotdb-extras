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

package org.apache.iotdb.relational.flink.utils;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.types.DataType;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class IoTDBUtilsBuildSelectQueryTest {

  private static final DataType ROW =
      ResolvedSchema.physical(
              new String[] {"a", "b"}, new DataType[] {DataTypes.INT(), DataTypes.STRING()})
          .toPhysicalRowDataType();

  @Test
  public void testSelectOnly() {
    assertEquals(
        "SELECT \"a\", \"b\" FROM \"sensor\"",
        IoTDBUtils.buildSelectQuery("sensor", ROW, Collections.emptyList(), -1L));
  }

  @Test
  public void testSelectWithFiltersAndLimit() {
    assertEquals(
        "SELECT \"a\", \"b\" FROM \"sensor\" WHERE (a = 1) AND (b = 'x') LIMIT 3",
        IoTDBUtils.buildSelectQuery("sensor", ROW, Arrays.asList("(a = 1)", "(b = 'x')"), 3L));
  }
}
