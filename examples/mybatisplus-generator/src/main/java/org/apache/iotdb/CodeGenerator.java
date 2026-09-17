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

package org.apache.iotdb;

import org.apache.iotdb.jdbc.IoTDBDataSource;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.DataSourceConfig;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.engine.VelocityTemplateEngine;

import javax.lang.model.SourceVersion;
import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Explicit development tool. Review target/generated-iotdb before copying generated files. */
public class CodeGenerator {
  public static void main(String[] args) throws SQLException {
    String database = System.getProperty("iotdb.database", "database1");
    IoTDBDataSource dataSource = new IoTDBDataSource();
    dataSource.setUrl(
        System.getProperty(
            "iotdb.url", "jdbc:iotdb://127.0.0.1:6667/" + database + "?sql_dialect=table"));
    dataSource.setUser(System.getProperty("iotdb.username", "root"));
    dataSource.setPassword(System.getProperty("iotdb.password", "root"));
    generate(
        dataSource,
        database,
        List.of(args.length == 0 ? new String[] {"table1", "table2"} : args),
        Path.of(System.getProperty("iotdb.output", "target/generated-iotdb")));
  }

  public static void generate(DataSource source, String database, List<String> tables, Path output)
      throws SQLException {
    Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
    try (Connection connection = source.getConnection()) {
      for (String table : tables) {
        List<Column> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet result =
                statement.executeQuery("DESC " + quote(database) + "." + quote(table))) {
          while (result.next()) {
            columns.add(
                new Column(
                    result.getString("ColumnName"),
                    result.getString("DataType"),
                    result.getString("Category")));
          }
        }
        schemas.put(table, templateData(columns));
      }
    }
    FastAutoGenerator.create(new DataSourceConfig.Builder(source))
        .globalConfig(
            builder ->
                builder
                    .author("IoTDB")
                    .disableOpenDir()
                    .outputDir(output.resolve("java").toString()))
        .packageConfig(
            builder ->
                builder
                    .parent("org.apache.iotdb")
                    .pathInfo(
                        Map.of(OutputFile.xml, output.resolve("resources/mappers").toString())))
        .dataSourceConfig(
            builder ->
                builder.typeConvertHandler(
                    (global, registry, info) -> {
                      switch (info.getJdbcType().TYPE_CODE) {
                        case Types.TIMESTAMP:
                          return DbColumnType.LONG;
                        case Types.FLOAT:
                          return DbColumnType.FLOAT;
                        default:
                          return registry.getColumnType(info);
                      }
                    }))
        .strategyConfig(
            builder -> {
              builder.addInclude(tables);
              builder
                  .entityBuilder()
                  .javaTemplate("/templates/iotdb-entity.java.vm")
                  .enableFileOverride();
              builder
                  .mapperBuilder()
                  .mapperTemplate("/templates/iotdb-mapper.java.vm")
                  .mapperXmlTemplate("/templates/iotdb-mapper.xml.vm")
                  .enableFileOverride();
              builder.serviceBuilder().disable();
              builder.controllerBuilder().disable();
            })
        .injectionConfig(
            builder ->
                builder.beforeOutputFile(
                    (table, data) -> {
                      Map<String, Object> schema = schemas.get(table.getName());
                      if (schema == null)
                        throw new IllegalArgumentException(
                            "Missing IoTDB schema: " + table.getName());
                      data.putAll(schema);
                      data.put("iotdbTable", xml(quote(table.getName())));
                      data.put("iotdbJavaTable", javaString(quote(table.getName())));
                    }))
        .templateEngine(new VelocityTemplateEngine())
        .execute();
  }

  static Map<String, Object> templateData(List<Column> columns) {
    java.util.Set<String> properties = new HashSet<>();
    for (Column column : columns) {
      String property = column.property();
      if (!SourceVersion.isIdentifier(property)
          || SourceVersion.isKeyword(property)
          || !properties.add(property)) {
        throw new IllegalArgumentException(
            "Column needs an explicit Java property mapping: " + column.name);
      }
    }
    List<Column> keys = columns.stream().filter(Column::key).collect(Collectors.toList());
    List<Column> tags =
        columns.stream().filter(c -> c.category.equals("TAG")).collect(Collectors.toList());
    List<Column> fields =
        columns.stream().filter(c -> c.category.equals("FIELD")).collect(Collectors.toList());
    List<Column> attributes =
        columns.stream().filter(c -> c.category.equals("ATTRIBUTE")).collect(Collectors.toList());
    if (keys.stream().noneMatch(c -> c.category.equals("TIME"))) {
      throw new IllegalArgumentException("IoTDB TIME column is required");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "iotdbFields",
        columns.stream()
            .map(
                c ->
                    "  @com.baomidou.mybatisplus.annotation.TableField(value = "
                        + javaString(quote(c.name))
                        + (c.handler() == null ? "" : ", typeHandler = " + c.handler() + ".class")
                        + ")\n"
                        + "  private "
                        + c.javaType()
                        + " "
                        + c.property()
                        + ";")
            .collect(Collectors.joining("\n\n")));
    data.put(
        "iotdbResults",
        columns.stream()
            .sorted(Comparator.comparing(c -> !c.key()))
            .map(
                c ->
                    "    <"
                        + (c.key() ? "id" : "result")
                        + " column=\""
                        + xml(c.name)
                        + "\" property=\""
                        + c.property()
                        + "\" jdbcType=\""
                        + c.jdbcType()
                        + "\""
                        + (c.handler() == null ? "" : " typeHandler=\"" + c.handler() + "\"")
                        + "/>")
            .collect(Collectors.joining("\n")));
    data.put(
        "iotdbColumns",
        columns.stream().map(c -> xml(quote(c.name))).collect(Collectors.joining(", ")));
    data.put(
        "iotdbKeyPredicate",
        keys.stream().map(c -> predicate(c, "key")).collect(Collectors.joining("\n")));
    data.put(
        "iotdbTagPredicate",
        tags.stream().map(c -> predicate(c, "row")).collect(Collectors.joining("\n")));
    // A table without TAGs represents one device.
    if (tags.isEmpty()) data.put("iotdbTagPredicate", "1 = 1");
    data.put(
        "iotdbUpsertColumns",
        keys.stream().map(c -> xml(quote(c.name)) + ",").collect(Collectors.joining("\n"))
            + "\n"
            + fields.stream()
                .map(
                    c ->
                        "<if test=\"row."
                            + c.property()
                            + " != null\">"
                            + xml(quote(c.name))
                            + ",</if>")
                .collect(Collectors.joining("\n")));
    data.put(
        "iotdbUpsertValues",
        keys.stream().map(c -> c.parameter("row") + ",").collect(Collectors.joining("\n"))
            + "\n"
            + fields.stream()
                .map(
                    c ->
                        "<if test=\"row."
                            + c.property()
                            + " != null\">"
                            + c.parameter("row")
                            + ",</if>")
                .collect(Collectors.joining("\n")));
    data.put(
        "iotdbAttributes",
        attributes.stream()
            .map(c -> xml(quote(c.name)) + " = " + c.parameter("row"))
            .collect(Collectors.joining(", ")));
    data.put("iotdbHasAttributes", !attributes.isEmpty());
    return data;
  }

  private static String predicate(Column c, String parameter) {
    return "<choose><when test=\""
        + parameter
        + "."
        + c.property()
        + " != null\">AND "
        + xml(quote(c.name))
        + " = "
        + c.parameter(parameter)
        + "</when><otherwise>AND "
        + xml(quote(c.name))
        + " IS NULL</otherwise></choose>";
  }

  static String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  static String javaString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  static String xml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  static class Column {
    final String name;
    final String type;
    final String category;

    Column(String name, String type, String category) {
      this.name = name;
      this.type = type;
      this.category = category;
    }

    boolean key() {
      return category.equals("TIME") || category.equals("TAG");
    }

    String property() {
      String[] parts = name.split("_");
      StringBuilder result = new StringBuilder(parts[0]);
      for (int i = 1; i < parts.length; i++) {
        if (!parts[i].isEmpty())
          result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
      }
      return result.toString();
    }

    String javaType() {
      switch (type) {
        case "TIMESTAMP":
        case "INT64":
          return "Long";
        case "INT32":
          return "Integer";
        case "FLOAT":
          return "Float";
        case "DOUBLE":
          return "Double";
        case "BOOLEAN":
          return "Boolean";
        case "DATE":
          return "java.time.LocalDate";
        case "BLOB":
          return "byte[]";
        case "STRING":
        case "TEXT":
          return "String";
        default:
          throw new IllegalArgumentException("Unsupported IoTDB type: " + type);
      }
    }

    String jdbcType() {
      switch (type) {
        case "INT64":
          return "BIGINT";
        case "INT32":
          return "INTEGER";
        case "STRING":
        case "TEXT":
          return "VARCHAR";
        default:
          return type;
      }
    }

    String handler() {
      if (type.equals("DATE")) return "org.apache.iotdb.mybatis.type.IoTDBLocalDateTypeHandler";
      if (type.equals("BLOB")) return "org.apache.iotdb.mybatis.type.IoTDBBlobTypeHandler";
      return null;
    }

    String parameter(String prefix) {
      return "#{"
          + prefix
          + "."
          + property()
          + ",jdbcType="
          + jdbcType()
          + (handler() == null ? "" : ",typeHandler=" + handler())
          + "}";
    }
  }
}
