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

package org.apache.iotdb.mybatis.type;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

@MappedTypes(LocalDate.class)
@MappedJdbcTypes(value = JdbcType.DATE, includeNullJdbcType = true)
public class IoTDBLocalDateTypeHandler extends BaseTypeHandler<LocalDate> {
  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, LocalDate value, JdbcType type) throws SQLException {
    statement.setDate(index, Date.valueOf(value));
  }

  @Override
  public LocalDate getNullableResult(ResultSet resultSet, String column) throws SQLException {
    Date value = resultSet.getDate(column);
    return resultSet.wasNull() ? null : toLocalDate(value);
  }

  @Override
  public LocalDate getNullableResult(ResultSet resultSet, int column) throws SQLException {
    Date value = resultSet.getDate(column);
    return resultSet.wasNull() ? null : toLocalDate(value);
  }

  @Override
  public LocalDate getNullableResult(CallableStatement statement, int column) throws SQLException {
    Date value = statement.getDate(column);
    return statement.wasNull() ? null : toLocalDate(value);
  }

  private LocalDate toLocalDate(Date value) {
    return value == null ? null : value.toLocalDate();
  }
}
