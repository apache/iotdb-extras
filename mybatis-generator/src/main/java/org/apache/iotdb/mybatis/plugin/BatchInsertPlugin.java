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

import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.PluginAdapter;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.Interface;
import org.mybatis.generator.api.dom.java.JavaVisibility;
import org.mybatis.generator.api.dom.java.Method;
import org.mybatis.generator.api.dom.java.Parameter;
import org.mybatis.generator.api.dom.xml.Attribute;
import org.mybatis.generator.api.dom.xml.Document;
import org.mybatis.generator.api.dom.xml.TextElement;
import org.mybatis.generator.api.dom.xml.XmlElement;
import org.mybatis.generator.codegen.mybatis3.MyBatis3FormattingUtilities;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class BatchInsertPlugin extends PluginAdapter {
  private int batchSize = 500;
  private List<String> warnings;

  @Override
  public boolean clientGenerated(Interface interfaze, IntrospectedTable introspectedTable) {
    if (canInsert(introspectedTable)) {
      batchInsertMethod(interfaze, introspectedTable);
    }

    return super.clientGenerated(interfaze, introspectedTable);
  }

  @Override
  public boolean sqlMapDocumentGenerated(Document document, IntrospectedTable introspectedTable) {
    if (canInsert(introspectedTable)) {
      addBatchInsertXml(document, introspectedTable);
    }
    return super.sqlMapDocumentGenerated(document, introspectedTable);
  }

  @Override
  public boolean validate(List<String> warnings) {
    this.warnings = warnings;
    try {
      batchSize = Integer.parseInt(properties.getProperty("batchSize", "500"));
      if (batchSize > 0) {
        return true;
      }
    } catch (NumberFormatException ignored) {
      // Report the invalid plugin setting through MBG's configuration warnings.
    }
    warnings.add("BatchInsertPlugin: batchSize must be a positive integer");
    return false;
  }

  @Override
  public void initialized(IntrospectedTable table) {
    if (table.getTableConfiguration().isInsertStatementEnabled()
        && insertColumns(table).isEmpty()) {
      warnings.add("BatchInsertPlugin: no insertable columns in " + table.getFullyQualifiedTable());
    }
  }

  private boolean canInsert(IntrospectedTable table) {
    return table.getTableConfiguration().isInsertStatementEnabled()
        && !insertColumns(table).isEmpty();
  }

  private List<IntrospectedColumn> insertColumns(IntrospectedTable table) {
    String incrementField = table.getTableConfigurationProperty("incrementField");
    return table.getAllColumns().stream()
        .filter(
            column ->
                !column.isIdentity()
                    && !column.isAutoIncrement()
                    && !column.isGeneratedAlways()
                    && !column.isGeneratedColumn())
        .filter(
            column ->
                incrementField == null
                    || !column.getActualColumnName().equalsIgnoreCase(incrementField.trim()))
        .collect(Collectors.toList());
  }

  private void batchInsertMethod(Interface interfaze, IntrospectedTable introspectedTable) {
    Set<FullyQualifiedJavaType> importedTypes = new TreeSet<>();
    importedTypes.add(FullyQualifiedJavaType.getNewListInstance());
    importedTypes.add(new FullyQualifiedJavaType("org.apache.ibatis.annotations.Param"));
    FullyQualifiedJavaType recordType = introspectedTable.getRules().calculateAllFieldsClass();
    importedTypes.add(recordType);

    Method ibsmethod = new Method("batchInsert");
    ibsmethod.setVisibility(JavaVisibility.PUBLIC);
    ibsmethod.setDefault(true);

    FullyQualifiedJavaType ibsReturnType = FullyQualifiedJavaType.getIntInstance();

    ibsmethod.setReturnType(ibsReturnType);

    ibsmethod.setName("batchInsert");

    FullyQualifiedJavaType paramType = FullyQualifiedJavaType.getNewListInstance();
    paramType.addTypeArgument(recordType);
    ibsmethod.addParameter(new Parameter(paramType, "records"));
    ibsmethod.addBodyLine(
        "if (records == null || records.stream().anyMatch(java.util.Objects::isNull)) {");
    ibsmethod.addBodyLine(
        "throw new IllegalArgumentException(\"records and its elements must not be null\");");
    ibsmethod.addBodyLine("}");
    ibsmethod.addBodyLine("int result = 0;");
    ibsmethod.addBodyLine("for (int start = 0; start < records.size();) {");
    ibsmethod.addBodyLine("int end = start + Math.min(" + batchSize + ", records.size() - start);");
    ibsmethod.addBodyLine("int count = batchInsertRows(records.subList(start, end));");
    ibsmethod.addBodyLine("result = count < 0 || result < 0 ? -1 : result + count;");
    ibsmethod.addBodyLine("start = end;");
    ibsmethod.addBodyLine("}");
    ibsmethod.addBodyLine("return result;");
    interfaze.addImportedTypes(importedTypes);

    interfaze.addMethod(ibsmethod);
    Method rowsMethod = new Method("batchInsertRows");
    rowsMethod.setVisibility(JavaVisibility.PUBLIC);
    rowsMethod.setAbstract(true);
    rowsMethod.setReturnType(FullyQualifiedJavaType.getIntInstance());
    rowsMethod.addParameter(new Parameter(paramType, "records", "@Param(\"records\")"));
    rowsMethod.addJavaDocLine("/** Internal single-batch statement; call batchInsert instead. */");
    interfaze.addMethod(rowsMethod);
  }

  private void addBatchInsertXml(Document document, IntrospectedTable introspectedTable) {
    List<IntrospectedColumn> columns = insertColumns(introspectedTable);

    XmlElement insertBatchElement = new XmlElement("insert");
    insertBatchElement.addAttribute(new Attribute("id", "batchInsertRows"));
    context.getCommentGenerator().addComment(insertBatchElement);
    String columnNames =
        columns.stream()
            .map(MyBatis3FormattingUtilities::getEscapedColumnName)
            .collect(Collectors.joining(", "));
    String parameters =
        columns.stream()
            .map(column -> MyBatis3FormattingUtilities.getParameterClause(column, "item."))
            .collect(Collectors.joining(", "));

    XmlElement foreachElement = new XmlElement("foreach");
    foreachElement.addAttribute(new Attribute("collection", "records"));
    foreachElement.addAttribute(new Attribute("index", "index"));
    foreachElement.addAttribute(new Attribute("item", "item"));
    foreachElement.addAttribute(new Attribute("separator", ","));
    insertBatchElement.addElement(
        new TextElement(
            "insert into "
                + introspectedTable.getFullyQualifiedTableNameAtRuntime()
                + " ("
                + columnNames
                + ") values"));
    foreachElement.addElement(new TextElement("(" + parameters + ")"));
    insertBatchElement.addElement(foreachElement);

    document.getRootElement().addElement(insertBatchElement);
  }
}
