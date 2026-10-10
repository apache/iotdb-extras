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

package org.apache.iotdb.mybatis.plugin.mapper;

import org.apache.iotdb.mybatis.plugin.model.Mix;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MixMapper {
  int deleteByPrimaryKey(@Param("time") Long time, @Param("deviceId") String deviceId);

  int insert(Mix row);

  Mix selectByPrimaryKey(@Param("time") Long time, @Param("deviceId") String deviceId);

  List<Mix> selectAll();

  default int batchInsert(List<Mix> records) {
    if (records == null || records.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("records and its elements must not be null");
    }
    int result = 0;
    for (int start = 0; start < records.size(); ) {
      int end = start + Math.min(500, records.size() - start);
      int count = batchInsertRows(records.subList(start, end));
      result = count < 0 || result < 0 ? -1 : result + count;
      start = end;
    }
    return result;
  }

  int batchInsertRows(@Param("records") List<Mix> records);
}
