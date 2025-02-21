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

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 指定查询字段
 */
public class InsertBatchPlugin extends PluginAdapter {

    /**
     * 修改Mapper类
     */
    @Override
    public boolean clientGenerated(Interface interfaze,
                                   IntrospectedTable introspectedTable) {
        batchInsertMethod(interfaze, introspectedTable);

        return super.clientGenerated(interfaze, introspectedTable);
    }

    /**
     * 修改Mapper.xml
     */
    @Override
    public boolean sqlMapDocumentGenerated(Document document, IntrospectedTable introspectedTable) {
        addBatchInsertXml(document, introspectedTable);
        return super.sqlMapDocumentGenerated(document, introspectedTable);
    }


    @Override
    public boolean validate(List<String> list) {
        return true;
    }


    private void batchInsertMethod(Interface interfaze, IntrospectedTable introspectedTable) {
        // 设置需要导入的类
        Set<FullyQualifiedJavaType> importedTypes = new TreeSet<>();
        importedTypes.add(FullyQualifiedJavaType.getNewListInstance());
        importedTypes.add(new FullyQualifiedJavaType(introspectedTable.getBaseRecordType()));

        Method ibsmethod = new Method("insertBatch");
        // 1.设置方法可见性
        ibsmethod.setVisibility(JavaVisibility.PUBLIC);
        ibsmethod.setAbstract(true);

        // 2.设置返回值类型
        FullyQualifiedJavaType ibsReturnType = FullyQualifiedJavaType.getIntInstance();

        //设置List的类型是实体类的对象
        ibsmethod.setReturnType(ibsReturnType);

        // 3.设置方法名
        ibsmethod.setName("insertBatch");

        // 4.设置参数列表
        FullyQualifiedJavaType paramType = FullyQualifiedJavaType.getNewListInstance();
        FullyQualifiedJavaType paramListType;
        paramListType = new FullyQualifiedJavaType(introspectedTable.getBaseRecordType());
        paramType.addTypeArgument(paramListType);
        ibsmethod.addParameter(new Parameter(paramType, "records", "@Param(\"records\")"));
        // 设置需要导入的类
        interfaze.addImportedTypes(importedTypes);

        interfaze.addMethod(ibsmethod);
    }

    private void addBatchInsertXml(Document document, IntrospectedTable introspectedTable) {
        List<IntrospectedColumn> columns = introspectedTable.getAllColumns();
        // 获得要自增的列名
        String incrementField = introspectedTable.getTableConfiguration().getProperties().getProperty("incrementField");
        if (incrementField != null) {
            incrementField = incrementField.toUpperCase();
        }

        XmlElement insertBatchElement = new XmlElement("insert");
        insertBatchElement.addAttribute(new Attribute("id", "insertBatch"));
        insertBatchElement.addAttribute(new Attribute("parameterType", "java.util.List"));

        StringBuilder sqlElement = new StringBuilder();
        StringBuilder javaPropertyAndDbType = new StringBuilder("(");
        for (IntrospectedColumn introspectedColumn : columns) {
            String columnName = introspectedColumn.getActualColumnName();
            // 不是自增字段的才会出现在批量插入中
            if (!columnName.toUpperCase().equals(incrementField)) {
                sqlElement.append(columnName + ",\n      ");
                javaPropertyAndDbType.append("\n      #{item." + introspectedColumn.getJavaProperty() + ",jdbcType=" + introspectedColumn.getJdbcTypeName() + "},");
            }
        }

        XmlElement foreachElement = new XmlElement("foreach");
        foreachElement.addAttribute(new Attribute("collection", "records"));
        foreachElement.addAttribute(new Attribute("index", "index"));
        foreachElement.addAttribute(new Attribute("item", "item"));
        foreachElement.addAttribute(new Attribute("separator", ","));
        insertBatchElement.addElement(new TextElement("insert into " + introspectedTable.getAliasedFullyQualifiedTableNameAtRuntime() + " ("));
        insertBatchElement.addElement(new TextElement("  " + sqlElement.delete(sqlElement.lastIndexOf(","), sqlElement.length()).toString()));
        insertBatchElement.addElement(new TextElement(") values "));
        foreachElement.addElement(new TextElement(javaPropertyAndDbType.delete(javaPropertyAndDbType.length() - 1, javaPropertyAndDbType.length()).append("\n      )").toString()));
        insertBatchElement.addElement(foreachElement);

        document.getRootElement().addElement(insertBatchElement);
    }

}