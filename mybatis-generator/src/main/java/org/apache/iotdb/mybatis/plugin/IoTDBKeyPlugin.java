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
import org.mybatis.generator.api.dom.xml.Attribute;
import org.mybatis.generator.api.dom.xml.TextElement;
import org.mybatis.generator.api.dom.xml.XmlElement;
import org.mybatis.generator.codegen.mybatis3.MyBatis3FormattingUtilities;
import org.mybatis.generator.config.TableConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts MBG's key-based statements to the IoTDB table model: nullable TAG components of the TIME +
 * TAG key become {@code IS NULL} predicates in SELECT/DELETE, and the UPDATE statements are dropped
 * because IoTDB UPDATE accepts neither {@code time} in the predicate nor FIELD columns in SET.
 */
public class IoTDBKeyPlugin extends PluginAdapter {
  // MBG hands the shared warnings list to validate() before any table callback.
  private List<String> warnings = new ArrayList<>();

  @Override
  public boolean validate(List<String> warnings) {
    this.warnings = warnings;
    return true;
  }

  @Override
  public void initialized(IntrospectedTable table) {
    TableConfiguration configuration = table.getTableConfiguration();
    if (configuration.isUpdateByPrimaryKeyStatementEnabled()
        || configuration.isUpdateByExampleStatementEnabled()) {
      warnings.add(
          "IoTDBKeyPlugin: not generating UPDATE statements for "
              + table.getFullyQualifiedTable()
              + "; IoTDB UPDATE cannot use time in the predicate or FIELD columns in SET. INSERT"
              + " the same key to change FIELD values and set enableUpdateByPrimaryKey and"
              + " enableUpdateByExample to false.");
      configuration.setUpdateByPrimaryKeyStatementEnabled(false);
      configuration.setUpdateByExampleStatementEnabled(false);
    }
  }

  @Override
  public boolean sqlMapSelectByPrimaryKeyElementGenerated(
      XmlElement element, IntrospectedTable table) {
    replaceKeyPredicate(element, table);
    return true;
  }

  @Override
  public boolean sqlMapDeleteByPrimaryKeyElementGenerated(
      XmlElement element, IntrospectedTable table) {
    replaceKeyPredicate(element, table);
    return true;
  }

  private void replaceKeyPredicate(XmlElement element, IntrospectedTable table) {
    // MBG emits the key WHERE clause as trailing text elements.
    int where = -1;
    for (int i = 0; i < element.getElements().size(); i++) {
      if (element.getElements().get(i) instanceof TextElement
          && ((TextElement) element.getElements().get(i))
              .getContent()
              .trim()
              .startsWith("where ")) {
        where = i;
        break;
      }
    }
    if (where < 0) return;
    element.getElements().subList(where, element.getElements().size()).clear();
    XmlElement predicate = new XmlElement("where");
    for (IntrospectedColumn column : table.getPrimaryKeyColumns()) {
      String name = MyBatis3FormattingUtilities.getEscapedColumnName(column);
      String parameter = MyBatis3FormattingUtilities.getParameterClause(column);
      if ("time".equalsIgnoreCase(column.getActualColumnName())) {
        predicate.addElement(new TextElement("AND " + name + " = " + parameter));
      } else {
        XmlElement choose = new XmlElement("choose");
        XmlElement when = new XmlElement("when");
        when.addAttribute(new Attribute("test", column.getJavaProperty() + " != null"));
        when.addElement(new TextElement("AND " + name + " = " + parameter));
        XmlElement otherwise = new XmlElement("otherwise");
        otherwise.addElement(new TextElement("AND " + name + " IS NULL"));
        choose.addElement(when);
        choose.addElement(otherwise);
        predicate.addElement(choose);
      }
    }
    element.addElement(predicate);
  }
}
