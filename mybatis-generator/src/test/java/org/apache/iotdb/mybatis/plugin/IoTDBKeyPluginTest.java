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
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.dom.DefaultXmlFormatter;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.xml.Attribute;
import org.mybatis.generator.api.dom.xml.Document;
import org.mybatis.generator.api.dom.xml.TextElement;
import org.mybatis.generator.api.dom.xml.XmlElement;
import org.mybatis.generator.codegen.mybatis3.IntrospectedTableMyBatis3Impl;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.ModelType;
import org.mybatis.generator.config.TableConfiguration;
import org.mybatis.generator.internal.rules.FlatModelRules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IoTDBKeyPluginTest {
  private final Context context = new Context(ModelType.FLAT);
  private final List<String> warnings = new ArrayList<>();
  private final IoTDBKeyPlugin plugin = new IoTDBKeyPlugin();

  private IntrospectedTable table() {
    context.addProperty("beginningDelimiter", "\"");
    context.addProperty("endingDelimiter", "\"");
    plugin.setContext(context);
    assertTrue(plugin.validate(warnings));
    IntrospectedTable table =
        new IntrospectedTableMyBatis3Impl() {
          @Override
          public String getFullyQualifiedTableNameAtRuntime() {
            return "mix";
          }
        };
    TableConfiguration configuration = new TableConfiguration(context);
    configuration.setTableName("mix");
    table.setTableConfiguration(configuration);
    table.setContext(context);
    table.setBaseRecordType("example.Mix");
    table.setRules(new FlatModelRules(table));
    column(table, "time", Types.TIMESTAMP, "TIMESTAMP", "java.lang.Long");
    column(table, "device_id", Types.VARCHAR, "VARCHAR", "java.lang.String");
    column(table, "temperature", Types.FLOAT, "FLOAT", "java.lang.Float");
    table.addPrimaryKeyColumn("time");
    table.addPrimaryKeyColumn("device_id");
    return table;
  }

  private void column(
      IntrospectedTable table, String name, int type, String jdbc, String javaType) {
    IntrospectedColumn column = new IntrospectedColumn();
    column.setContext(context);
    column.setActualColumnName(name);
    column.setJavaProperty(
        name.equals("device_id") ? "deviceId" : name); // MBG camel-cases the property name
    column.setColumnNameDelimited(true); // matches delimitAllColumns in the example configs
    column.setJdbcType(type);
    column.setJdbcTypeName(jdbc);
    column.setFullyQualifiedJavaType(new FullyQualifiedJavaType(javaType));
    table.addColumn(column);
  }

  /** Mirrors the trailing "where ... and ..." text elements MBG emits for the key predicate. */
  private XmlElement keyedStatement(String tag, String id, String prefix) {
    XmlElement element = new XmlElement(tag);
    element.addAttribute(new Attribute("id", id));
    if (tag.equals("select")) {
      element.addAttribute(new Attribute("resultType", "map"));
    }
    element.addElement(new TextElement(prefix + " \"mix\""));
    element.addElement(new TextElement("where \"time\" = #{time,jdbcType=TIMESTAMP}"));
    element.addElement(new TextElement("  and \"device_id\" = #{deviceId,jdbcType=VARCHAR}"));
    return element;
  }

  private Configuration parse(XmlElement... statements) {
    Document document =
        new Document(
            "-//mybatis.org//DTD Mapper 3.0//EN", "https://mybatis.org/dtd/mybatis-3-mapper.dtd");
    XmlElement root = new XmlElement("mapper");
    root.addAttribute(new Attribute("namespace", "example.MixMapper"));
    for (XmlElement statement : statements) {
      root.addElement(statement);
    }
    document.setRootElement(root);
    Configuration configuration = new Configuration();
    new XMLMapperBuilder(
            new ByteArrayInputStream(
                new DefaultXmlFormatter()
                    .getFormattedContent(document)
                    .getBytes(StandardCharsets.UTF_8)),
            configuration,
            "mapper.xml",
            configuration.getSqlFragments())
        .parse();
    return configuration;
  }

  @Test
  public void rewritesKeyPredicatesSoNullTagsMatchWithIsNull() {
    IntrospectedTable table = table();
    XmlElement select = keyedStatement("select", "selectByPrimaryKey", "select * from");
    XmlElement delete = keyedStatement("delete", "deleteByPrimaryKey", "delete from");
    assertTrue(plugin.sqlMapSelectByPrimaryKeyElementGenerated(select, table));
    assertTrue(plugin.sqlMapDeleteByPrimaryKeyElementGenerated(delete, table));
    Configuration configuration = parse(select, delete);
    for (String statement : new String[] {"selectByPrimaryKey", "deleteByPrimaryKey"}) {
      Map<String, Object> key = new HashMap<>();
      key.put("time", 1700000000123L);
      key.put("deviceId", "d1");
      BoundSql bound =
          configuration.getMappedStatement("example.MixMapper." + statement).getBoundSql(key);
      String sql = bound.getSql().replaceAll("\\s+", " ");
      assertTrue(sql, sql.contains("WHERE \"time\" = ? AND \"device_id\" = ?"));
      assertEquals(2, bound.getParameterMappings().size());
      key.put("deviceId", null);
      bound = configuration.getMappedStatement("example.MixMapper." + statement).getBoundSql(key);
      sql = bound.getSql().replaceAll("\\s+", " ");
      assertTrue(sql, sql.contains("WHERE \"time\" = ? AND \"device_id\" IS NULL"));
      assertEquals(1, bound.getParameterMappings().size());
    }
  }

  @Test
  public void leavesStatementsWithoutAKeyPredicateAlone() {
    IntrospectedTable table = table();
    XmlElement select = new XmlElement("select");
    select.addAttribute(new Attribute("id", "selectAll"));
    select.addElement(new TextElement("select * from \"mix\""));
    assertTrue(plugin.sqlMapSelectByPrimaryKeyElementGenerated(select, table));
    assertEquals(1, select.getElements().size());
  }

  @Test
  public void disablesUpdateStatementsWithAWarning() {
    IntrospectedTable table = table();
    TableConfiguration configuration = table.getTableConfiguration();
    configuration.setUpdateByPrimaryKeyStatementEnabled(true);
    configuration.setUpdateByExampleStatementEnabled(true);
    plugin.initialized(table);
    assertFalse(configuration.isUpdateByPrimaryKeyStatementEnabled());
    assertFalse(configuration.isUpdateByExampleStatementEnabled());
    assertFalse(table.getRules().generateUpdateByPrimaryKeyWithoutBLOBs());
    assertFalse(table.getRules().generateUpdateByPrimaryKeySelective());
    assertFalse(table.getRules().generateUpdateByExampleWithoutBLOBs());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0), warnings.get(0).contains("not generating UPDATE statements"));

    plugin.initialized(table);
    assertEquals("already disabled tables do not warn again", 1, warnings.size());
  }
}
