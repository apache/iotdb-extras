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

package org.apache.iotdb.mybatis.plugin;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.dom.DefaultJavaFormatter;
import org.mybatis.generator.api.dom.DefaultXmlFormatter;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.Interface;
import org.mybatis.generator.api.dom.java.JavaVisibility;
import org.mybatis.generator.api.dom.xml.Attribute;
import org.mybatis.generator.api.dom.xml.Document;
import org.mybatis.generator.api.dom.xml.XmlElement;
import org.mybatis.generator.codegen.mybatis3.IntrospectedTableMyBatis3Impl;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.ModelType;
import org.mybatis.generator.config.TableConfiguration;
import org.mybatis.generator.internal.rules.FlatModelRules;
import org.mybatis.generator.internal.rules.HierarchicalModelRules;

import javax.tools.ToolProvider;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BatchInsertPluginTest {
  private final Context context = new Context(ModelType.FLAT);
  private final List<String> warnings = new ArrayList<>();
  private final BatchInsertPlugin plugin = new BatchInsertPlugin();

  private IntrospectedTable table() {
    context.addProperty("beginningDelimiter", "\"");
    context.addProperty("endingDelimiter", "\"");
    plugin.setContext(context);
    assertTrue(plugin.validate(warnings));
    IntrospectedTable table =
        new IntrospectedTableMyBatis3Impl() {
          @Override
          public String getFullyQualifiedTableNameAtRuntime() {
            return "measurements";
          }

          @Override
          public String getAliasedFullyQualifiedTableNameAtRuntime() {
            return "measurements m";
          }
        };
    TableConfiguration configuration = new TableConfiguration(context);
    configuration.setTableName("measurements");
    table.setTableConfiguration(configuration);
    table.setContext(context);
    table.setBaseRecordType("example.Measurement");
    table.setRecordWithBLOBsType("example.MeasurementWithBLOBs");
    table.setRules(new FlatModelRules(table));
    column(table, "time", Types.TIMESTAMP, "TIMESTAMP", "java.lang.Long");
    return table;
  }

  private IntrospectedColumn column(
      IntrospectedTable table, String name, int type, String jdbc, String javaType) {
    IntrospectedColumn column = new IntrospectedColumn();
    column.setContext(context);
    column.setActualColumnName(name);
    column.setJavaProperty(name);
    column.setJdbcType(type);
    column.setJdbcTypeName(jdbc);
    column.setFullyQualifiedJavaType(new FullyQualifiedJavaType(javaType));
    table.addColumn(column);
    return column;
  }

  private Interface mapper(IntrospectedTable table) {
    Interface mapper = new Interface("example.MeasurementMapper");
    mapper.setVisibility(JavaVisibility.PUBLIC);
    plugin.clientGenerated(mapper, table);
    return mapper;
  }

  private String xml(IntrospectedTable table) {
    Document document =
        new Document(
            "-//mybatis.org//DTD Mapper 3.0//EN", "https://mybatis.org/dtd/mybatis-3-mapper.dtd");
    XmlElement root = new XmlElement("mapper");
    root.addAttribute(new Attribute("namespace", "example.MeasurementMapper"));
    document.setRootElement(root);
    plugin.sqlMapDocumentGenerated(document, table);
    return new DefaultXmlFormatter().getFormattedContent(document);
  }

  @Test
  public void generatedMapperCompilesAndGuardsAndSplitsBatches() throws Exception {
    IntrospectedTable table = table();
    Properties properties = new Properties();
    properties.setProperty("batchSize", "2");
    plugin.setProperties(properties);
    assertTrue(plugin.validate(warnings));
    Path dir = Files.createTempDirectory("iotdb-mbg-test-");
    try {
      Path source = Files.createDirectories(dir.resolve("example"));
      Files.writeString(
          source.resolve("Measurement.java"), "package example; public class Measurement {}");
      Files.writeString(
          source.resolve("MeasurementMapper.java"),
          new DefaultJavaFormatter().getFormattedContent(mapper(table)));
      assertEquals(
          0,
          ToolProvider.getSystemJavaCompiler()
              .run(
                  null,
                  null,
                  null,
                  "-proc:none",
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  dir.toString(),
                  source.resolve("Measurement.java").toString(),
                  source.resolve("MeasurementMapper.java").toString()));
      try (URLClassLoader loader =
          new URLClassLoader(
              new java.net.URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
        Class<?> mapper = loader.loadClass("example.MeasurementMapper");
        Object row = loader.loadClass("example.Measurement").getConstructor().newInstance();
        List<Integer> chunks = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean unknownCount =
            new java.util.concurrent.atomic.AtomicBoolean();
        Object instance =
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {mapper},
                (proxy, method, args) -> {
                  if (method.isDefault())
                    return InvocationHandler.invokeDefault(proxy, method, args);
                  chunks.add(((List<?>) args[0]).size());
                  return unknownCount.get() ? -1 : ((List<?>) args[0]).size();
                });
        java.lang.reflect.Method insert = mapper.getMethod("batchInsert", List.class);
        assertEquals(0, insert.invoke(instance, List.of()));
        assertTrue(chunks.isEmpty());
        assertEquals(5, insert.invoke(instance, List.of(row, row, row, row, row)));
        assertEquals(List.of(2, 2, 1), chunks);
        unknownCount.set(true);
        assertEquals(-1, insert.invoke(instance, List.of(row, row, row)));
        chunks.clear();
        InvocationTargetException failure =
            assertThrows(
                InvocationTargetException.class,
                () -> insert.invoke(instance, Arrays.asList(row, row, row, null)));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(chunks.isEmpty());
        assertThrows(
            InvocationTargetException.class, () -> insert.invoke(instance, new Object[] {null}));
      }
    } finally {
      try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
        for (Path path :
            files
                .sorted(java.util.Comparator.reverseOrder())
                .collect(java.util.stream.Collectors.toList())) {
          Files.delete(path);
        }
      }
    }
  }

  @Test
  public void preservesEscapingHandlersAndParameterCounts() {
    IntrospectedTable table = table();
    IntrospectedColumn order = column(table, "order", Types.BIGINT, "BIGINT", "java.lang.Long");
    order.setColumnNameDelimited(true);
    order.setTypeHandler("org.apache.ibatis.type.LongTypeHandler");
    String xml = xml(table);
    assertTrue(xml.contains("\"order\""));
    assertTrue(xml.contains("typeHandler=org.apache.ibatis.type.LongTypeHandler"));
    assertFalse(xml.contains("measurements m"));
    Configuration configuration = new Configuration();
    new XMLMapperBuilder(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
            configuration,
            "mapper.xml",
            configuration.getSqlFragments())
        .parse();
    org.apache.ibatis.mapping.BoundSql sql =
        configuration
            .getMappedStatement("example.MeasurementMapper.batchInsertRows")
            .getBoundSql(
                Map.of(
                    "records",
                    List.of(Map.of("time", 1L, "order", 2L), Map.of("time", 3L, "order", 4L))));
    assertEquals(4, sql.getParameterMappings().size());
    assertTrue(sql.getSql().replaceAll("\\s+", " ").contains("(?, ?)"));
  }

  @Test
  public void excludesGeneratedColumnsAndSkipsTablesWithoutInsertableColumns() {
    IntrospectedTable table = table();
    column(table, "identity", Types.BIGINT, "BIGINT", "java.lang.Long").setIdentity(true);
    column(table, "generated", Types.BIGINT, "BIGINT", "java.lang.Long").setGeneratedAlways(true);
    column(table, "automatic", Types.BIGINT, "BIGINT", "java.lang.Long").setAutoIncrement(true);
    assertFalse(xml(table).contains("identity"));
    assertFalse(xml(table).contains("item.generated"));
    assertFalse(xml(table).contains("item.automatic"));
    table.getTableConfiguration().addProperty("incrementField", "TIME");
    plugin.initialized(table);
    assertTrue(warnings.get(0).contains("no insertable columns"));
    assertTrue(mapper(table).getMethods().isEmpty());
    assertFalse(xml(table).contains("<insert"));
  }

  @Test
  public void usesTheAllFieldsTypeForHierarchicalBlobModels() {
    IntrospectedTable table = table();
    column(table, "payload", Types.BLOB, "BLOB", "byte[]");
    table.setRules(new HierarchicalModelRules(table));
    assertTrue(
        mapper(table)
            .getMethods()
            .get(0)
            .getParameters()
            .get(0)
            .getType()
            .getFullyQualifiedName()
            .contains("MeasurementWithBLOBs"));
  }

  @Test
  public void honorsDisabledInsertAndRejectsInvalidBatchSizes() {
    IntrospectedTable table = table();
    table.getTableConfiguration().setInsertStatementEnabled(false);
    assertTrue(mapper(table).getMethods().isEmpty());
    for (String value : List.of("0", "-1", "not-a-number")) {
      Properties properties = new Properties();
      properties.setProperty("batchSize", value);
      plugin.setProperties(properties);
      assertFalse(plugin.validate(warnings));
    }
  }
}
