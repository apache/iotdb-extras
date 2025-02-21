/*
 *    Copyright 2006-2025 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.generator.internal.types;

import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/**
 * 默认 {@linkplain JavaTypeResolverDefaultImpl TypeResolver} 的扩展； 主要用于将 DB
 * 中的类型转换成相应 jdbc 类型，但其实可以通过 &lt;table&gt; 中的 &lt; columnOverride
 * 属性来进行更改（不过使用的本类约定的方式更加方便）
 */
public class IoTDBJavaTypeResolver extends JavaTypeResolverDefaultImpl {
    protected Map<String, Integer> typeExtMap;

    public IoTDBJavaTypeResolver() {
        super();
        typeExtMap = new HashMap<>();
        initTypeSet();
    }

    @Override
    public FullyQualifiedJavaType calculateJavaType(IntrospectedColumn introspectedColumn) {
        /**
         * 读取自定义配置，value 值必须为 className，并覆盖默认 typeMap
         */
        for (String jdbcType : typeExtMap.keySet()) {

            String value = properties.getProperty(jdbcType);

            if (hasText(value)) {
                typeMap.put(typeExtMap.get(jdbcType), new JdbcTypeInformation(jdbcType.substring(jdbcType.indexOf(".") + 1), new FullyQualifiedJavaType(value)));
            }
        }
        /**
         * 之后回调父类方法
         */
        return super.calculateJavaType(introspectedColumn);
    }

    private void initTypeSet() {
        typeExtMap.put("jdbcType.BOOLEAN", Types.BOOLEAN);
        typeExtMap.put("jdbcType.TINYINT", Types.TINYINT);
        typeExtMap.put("jdbcType.INTEGER", Types.INTEGER);
        typeExtMap.put("jdbcType.INT32", Types.INTEGER);
        typeExtMap.put("jdbcType.BIGINT", Types.BIGINT);
        typeExtMap.put("jdbcType.INT64", Types.BIGINT);
        typeExtMap.put("jdbcType.FLOAT", Types.FLOAT);
        typeExtMap.put("jdbcType.DOUBLE", Types.DOUBLE);
        typeExtMap.put("jdbcType.CHAR", Types.CHAR);
        typeExtMap.put("jdbcType.TEXT", Types.LONGNVARCHAR);
        typeExtMap.put("jdbcType.STRING", Types.VARCHAR);
        typeExtMap.put("jdbcType.BINARY", Types.BINARY);
        typeExtMap.put("jdbcType.BIT", Types.BIT);
        typeExtMap.put("jdbcType.BLOB", Types.BLOB);
        typeExtMap.put("jdbcType.TIME", Types.TIME);
        typeExtMap.put("jdbcType.TIMESTAMP", Types.TIMESTAMP);
        typeExtMap.put("jdbcType.DATE", Types.DATE);

        typeExtMap.put("jdbcType.ARRAY", Types.ARRAY);
        typeExtMap.put("jdbcType.CLOB", Types.CLOB);
        typeExtMap.put("jdbcType.DATALINK", Types.DATALINK);
        typeExtMap.put("jdbcType.DECIMAL", Types.DECIMAL);
        typeExtMap.put("jdbcType.DISTINCT", Types.DISTINCT);
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
        typeExtMap.put("jdbcType.VARBINARY", Types.VARBINARY);

    }

    public static boolean hasText(String text) {
        if (text != null && text.trim().length() > 0) {
            return true;
        }
        return false;
    }

    @Override
    public String calculateJdbcTypeName(IntrospectedColumn introspectedColumn) {
        String answer;
        JdbcTypeInformation jdbcTypeInformation = typeMap.get(introspectedColumn.getJdbcType());

        if (jdbcTypeInformation == null) {
            switch (introspectedColumn.getJdbcType()) {
                case Types.BOOLEAN:
                    answer = "BOOLEAN";
                    break;
                case Types.INTEGER:
                    answer = "INTEGER";
                    break;
                case Types.BIGINT:
                    answer = "BIGINT";
                    break;
                case Types.FLOAT:
                    answer = "FLOAT";
                    break;
                case Types.DOUBLE:
                    answer = "DOUBLE";
                    break;
                case Types.LONGVARCHAR:
                    answer = "TEXT";
                    break;
                case Types.VARCHAR:
                    answer = "STRING";
                    break;
                case Types.BLOB:
                    answer = "BLOB";
                    break;
                case Types.TIMESTAMP:
                    answer = "TIMESTAMP";
                    break;
                case Types.DATE:
                    answer = "DATE";
                    break;
                default:
                    answer = null;
                    break;
            }
        } else {
            answer = jdbcTypeInformation.getJdbcTypeName();
        }

        return answer;
    }
}
