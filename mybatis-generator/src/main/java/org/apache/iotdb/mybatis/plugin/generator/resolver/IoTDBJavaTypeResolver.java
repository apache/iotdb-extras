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
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.internal.types.JavaTypeResolverDefaultImpl;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

public class IoTDBJavaTypeResolver extends JavaTypeResolverDefaultImpl {
  protected Map<String, Integer> typeExtMap;

  public IoTDBJavaTypeResolver() {
    super();
    typeExtMap = new HashMap<>();
    super.typeMap.put(
        Types.TIMESTAMP,
        new JdbcTypeInformation("TIMESTAMP", new FullyQualifiedJavaType(Long.class.getName())));
    initTypeSet();
  }

  @Override
  public FullyQualifiedJavaType calculateJavaType(IntrospectedColumn introspectedColumn) {

    for (String jdbcType : typeExtMap.keySet()) {

      String value = properties.getProperty(jdbcType);

      if (hasText(value)) {
        typeMap.put(
            typeExtMap.get(jdbcType),
            new JdbcTypeInformation(
                jdbcType.substring(jdbcType.indexOf(".") + 1), new FullyQualifiedJavaType(value)));
      }
    }

    return super.calculateJavaType(introspectedColumn);
  }

  private void initTypeSet() {
    typeExtMap.put("jdbcType.ARRAY", Types.ARRAY);
    typeExtMap.put("jdbcType.BIGINT", Types.BIGINT);
    typeExtMap.put("jdbcType.BINARY", Types.BINARY);
    typeExtMap.put("jdbcType.BIT", Types.BIT);
    typeExtMap.put("jdbcType.BLOB", Types.BLOB);
    typeExtMap.put("jdbcType.BOOLEAN", Types.BOOLEAN);
    typeExtMap.put("jdbcType.CHAR", Types.CHAR);
    typeExtMap.put("jdbcType.CLOB", Types.CLOB);
    typeExtMap.put("jdbcType.DATALINK", Types.DATALINK);
    typeExtMap.put("jdbcType.DATE", Types.DATE);
    typeExtMap.put("jdbcType.DECIMAL", Types.DECIMAL);
    typeExtMap.put("jdbcType.DISTINCT", Types.DISTINCT);
    typeExtMap.put("jdbcType.DOUBLE", Types.DOUBLE);
    typeExtMap.put("jdbcType.FLOAT", Types.FLOAT);
    typeExtMap.put("jdbcType.INTEGER", Types.INTEGER);
    typeExtMap.put("jdbcType.JAVA_OBJECT", Types.JAVA_OBJECT);
    typeExtMap.put("jdbcType.LONGNVARCHAR", Types.LONGNVARCHAR);
    typeExtMap.put("jdbcType.LONGVARBINARY", Types.LONGVARBINARY);
    typeExtMap.put("jdbcType.LONGVARCHAR", Types.LONGVARCHAR);
    typeExtMap.put("jdbcType.NCHAR", Types.NCHAR);
    typeExtMap.put("jdbcType.NCLOB", Types.NCLOB);
    typeExtMap.put("jdbcType.NVARCHAR", Types.NVARCHAR);
    typeExtMap.put("jdbcType.NULL", Types.NULL);
    typeExtMap.put("jdbcType.NUMERIC", Types.NUMERIC);
    typeExtMap.put("jdbcType.OTHER", Types.OTHER);
    typeExtMap.put("jdbcType.REAL", Types.REAL);
    typeExtMap.put("jdbcType.REF", Types.REF);
    typeExtMap.put("jdbcType.SMALLINT", Types.SMALLINT);
    typeExtMap.put("jdbcType.STRUCT", Types.STRUCT);
    typeExtMap.put("jdbcType.TIME", Types.TIME);
    typeExtMap.put("jdbcType.TIMESTAMP", Types.TIMESTAMP);
    typeExtMap.put("jdbcType.TINYINT", Types.TINYINT);
    typeExtMap.put("jdbcType.VARBINARY", Types.VARBINARY);
    typeExtMap.put("jdbcType.VARCHAR", Types.VARCHAR);
  }

  public static boolean hasText(String text) {
    if (text != null && text.trim().length() > 0) {
      return true;
    }
    return false;
  }
}
