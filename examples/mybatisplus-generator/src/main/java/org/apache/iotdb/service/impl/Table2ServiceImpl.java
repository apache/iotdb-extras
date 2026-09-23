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

package org.apache.iotdb.service.impl;

import org.apache.iotdb.entity.Table2;
import org.apache.iotdb.mapper.Table2Mapper;
import org.apache.iotdb.service.Table2Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class Table2ServiceImpl implements Table2Service {
  private final Table2Mapper mapper;

  public Table2ServiceImpl(Table2Mapper mapper) {
    this.mapper = mapper;
  }

  private void requireTime(Table2 row) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(row.getTime(), "time is required; supply raw server-precision ticks");
    // Null TAGs are valid IoTDB key components; the XML generates IS NULL predicates for them.
  }

  @Override
  public int insert(Table2 row) {
    requireTime(row);
    return mapper.insert(row);
  }

  @Override
  public Table2 selectByKey(Table2 key) {
    requireTime(key);
    return mapper.selectByKey(key);
  }

  @Override
  public int deleteByKey(Table2 key) {
    requireTime(key);
    return mapper.deleteByKey(key);
  }

  @Override
  public int upsertFields(Table2 row) {
    requireTime(row);
    if (row.getTemperature() == null
        && row.getHumidity() == null
        && row.getStatus() == null
        && row.getArrivalTime() == null
        && row.getReadingDate() == null
        && row.getPayload() == null) {
      throw new IllegalArgumentException("at least one non-null FIELD value is required");
    }
    return mapper.upsertFields(row);
  }

  @Override
  public int updateAttributes(Table2 row) {
    Objects.requireNonNull(row, "row");
    // Attributes belong to the device (all TAGs), not to an individual timestamp.
    return mapper.updateAttributes(row);
  }

  @Override
  public List<Table2> list() {
    return mapper.selectList(
        new QueryWrapper<Table2>()
            .orderByAsc("time", "region", "plant_id", "device_id")
            .last("LIMIT 1000"));
  }
}
