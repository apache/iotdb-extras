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

package org.apache.iotdb.mybatis.plugin.generator.resolver;

import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.internal.types.JavaTypeResolverDefaultImpl;

import java.sql.Types;

public class JavaTypeResolverSelfImpl extends JavaTypeResolverDefaultImpl {

  @Override
  public String calculateJdbcTypeName(IntrospectedColumn introspectedColumn) {
    String answer;
    JdbcTypeInformation jdbcTypeInformation = typeMap.get(introspectedColumn.getJdbcType());

    if (jdbcTypeInformation == null) {
      switch (introspectedColumn.getJdbcType()) {
        case Types.DECIMAL:
          answer = "DECIMAL";
          break;
        case Types.NUMERIC:
          answer = "NUMERIC";
          break;
        case Types.DATE:
          answer = "TIMESTAMP";
          break;
        default:
          answer = null;
          break;
      }
    } else {
      if ("DATE".equals(jdbcTypeInformation.getJdbcTypeName())) {
        answer = "TIMESTAMP";
      } else {
        answer = jdbcTypeInformation.getJdbcTypeName();
      }
    }

    return answer;
  }
}
