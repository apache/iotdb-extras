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
package org.mybatis.generator.plugins;

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

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.mybatis.generator.internal.util.messages.Messages.getString;

/**
 * 删除指定行
 */
public class DeleteByTimePlugin extends PluginAdapter {

    /**
     * 修改Mapper类
     */
    @Override
    public boolean clientGenerated(Interface interfaze,
                                   IntrospectedTable introspectedTable) {
        deleteByTimeMethod(interfaze, introspectedTable);

        return super.clientGenerated(interfaze, introspectedTable);
    }



    @Override
    public boolean validate(List<String> list) {
        return true;
    }

    private void deleteByTimeMethod(Interface interfaze, IntrospectedTable introspectedTable) {
        Set<FullyQualifiedJavaType> importedTypes = new TreeSet<>();
        importedTypes.add(FullyQualifiedJavaType.getIntInstance()); // 导入 int 类型
        importedTypes.add(FullyQualifiedJavaType.getDateInstance()); // 导入 Date 类型

        // 创建方法
        Method method = new Method("deleteByTime");
        method.setVisibility(JavaVisibility.PUBLIC); // 设置方法可见性为 public
        method.setAbstract(true);

        // 设置返回值类型为 int
        method.setReturnType(FullyQualifiedJavaType.getIntInstance   ());

        // 添加参数
        FullyQualifiedJavaType paramType = FullyQualifiedJavaType.getDateInstance();
        method.addParameter(new Parameter(paramType, "time","@Param(\"time\")")); // 添加时间参数

        // 添加方法到接口
        interfaze.addImportedTypes(importedTypes); // 添加需要导入的类型
        interfaze.addMethod(method); // 添加方法到接口
    }

    @Override
    public boolean sqlMapDocumentGenerated(Document document, IntrospectedTable introspectedTable) {
        // 获取表名
        String tableName = introspectedTable.getFullyQualifiedTableNameAtRuntime();

        // 创建一个新的 XML 元素
        XmlElement deleteElement = new XmlElement("delete");

        // 设置 id 属性
        deleteElement.addAttribute(new Attribute("id", "deleteByTime"));
        deleteElement.addAttribute(new Attribute("parameterType","date"));


        // 添加 SQL 语句
        deleteElement.addElement(new TextElement("delete from " + tableName + " where time = #{time}"));

        // 将新的 XML 元素添加到 document 中
        document.getRootElement().addElement(deleteElement);

        return super.sqlMapDocumentGenerated(document, introspectedTable);
    }
}