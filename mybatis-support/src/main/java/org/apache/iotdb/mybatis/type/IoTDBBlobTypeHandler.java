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

import java.io.ByteArrayInputStream;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(byte[].class)
@MappedJdbcTypes(
    value = {JdbcType.BLOB, JdbcType.BINARY, JdbcType.VARBINARY},
    includeNullJdbcType = true)
public class IoTDBBlobTypeHandler extends BaseTypeHandler<byte[]> {
  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, byte[] value, JdbcType type) throws SQLException {
    // IoTDB 2.0.11 encodes this overload as a hexadecimal BLOB literal. setBytes decodes text.
    statement.setBinaryStream(index, new ByteArrayInputStream(value), value.length);
  }

  @Override
  public byte[] getNullableResult(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getBytes(column);
  }

  @Override
  public byte[] getNullableResult(ResultSet resultSet, int column) throws SQLException {
    return resultSet.getBytes(column);
  }

  @Override
  public byte[] getNullableResult(CallableStatement statement, int column) throws SQLException {
    return statement.getBytes(column);
  }
}
