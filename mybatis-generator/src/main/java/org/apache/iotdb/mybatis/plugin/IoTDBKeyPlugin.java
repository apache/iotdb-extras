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

import java.util.List;

/** Preserves nullable TAG components in generated TIME + TAG key predicates. */
public class IoTDBKeyPlugin extends PluginAdapter {
  @Override
  public boolean validate(List<String> warnings) {
    return true;
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
