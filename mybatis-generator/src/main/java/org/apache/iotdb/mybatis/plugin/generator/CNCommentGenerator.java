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

package org.apache.iotdb.mybatis.plugin.generator;

import org.apache.iotdb.mybatis.plugin.util.DateUtil;
import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.dom.java.CompilationUnit;
import org.mybatis.generator.api.dom.java.Field;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.JavaVisibility;
import org.mybatis.generator.api.dom.java.TopLevelClass;
import org.mybatis.generator.internal.DefaultCommentGenerator;
import org.mybatis.generator.internal.util.StringUtility;

import java.util.Date;
import java.util.Properties;

public class CNCommentGenerator extends DefaultCommentGenerator {
    private Properties properties;

    @Override
    public void addConfigurationProperties(Properties properties) {
        super.addConfigurationProperties(properties);
        this.properties = new Properties();
        this.properties.putAll(properties);
    }

    @Override
    public void addJavaFileComment(CompilationUnit compilationUnit) {
        compilationUnit.addFileCommentLine("/**");

        String copyright = " * Copyright From 2025. IoTDB.";
        compilationUnit.addFileCommentLine(copyright);

        compilationUnit.addFileCommentLine(" * ");
        compilationUnit.addFileCommentLine(" * " + compilationUnit.getType().getShortNameWithoutTypeArguments() + ".java");
        compilationUnit.addFileCommentLine(" */");
    }

    @Override
    public void addModelClassComment(TopLevelClass topLevelClass, IntrospectedTable introspectedTable) {
        StringBuilder sb = new StringBuilder();

        topLevelClass.addJavaDocLine("/**");
        topLevelClass.addJavaDocLine(" *");

        String remarks = introspectedTable.getRemarks();
        if (StringUtility.stringHasValue(remarks)) {
            String[] remarkLines = remarks.split(System.getProperty("line.separator"));
            for (String remarkLine : remarkLines) {
                topLevelClass.addJavaDocLine(" * " + remarkLine);
            }
            sb.append(" * ");
        }

        sb.append("table: ");
        sb.append(introspectedTable.getFullyQualifiedTable());
        sb.append(" of model class");
        topLevelClass.addJavaDocLine(sb.toString());
        topLevelClass.addJavaDocLine(" *");

        String author = "IoTDB";
        if (properties.containsKey("author")) {
            author = properties.getProperty("author");
        }

        topLevelClass.addJavaDocLine(" * @author " + author);
        topLevelClass.addJavaDocLine(" * @date " + DateUtil.date2Str(new Date()));
        topLevelClass.addJavaDocLine(" */");
        FullyQualifiedJavaType serializable = new FullyQualifiedJavaType("java.io.Serializable");

        topLevelClass.addImportedType(serializable);
        topLevelClass.addSuperInterface(serializable);

        final FullyQualifiedJavaType qualifiedJavaType = new FullyQualifiedJavaType("long");
        Field serialVersionUID = new Field("serialVersionUID",qualifiedJavaType);
        serialVersionUID.setVisibility(JavaVisibility.PRIVATE);
        serialVersionUID.setStatic(true);
        serialVersionUID.setFinal(true);
        serialVersionUID.setName("serialVersionUID");
        serialVersionUID.setType(qualifiedJavaType);
        serialVersionUID.setInitializationString("1L");
        sb = new StringBuilder();
        sb.append("/**\n    ");
        sb.append(" * class serial version id\n    ");
        sb.append(" */");
        serialVersionUID.addJavaDocLine(sb.toString());

        topLevelClass.addField(serialVersionUID);
    }

    @Override
    public void addFieldComment(Field field, IntrospectedTable introspectedTable, IntrospectedColumn introspectedColumn) {
        StringBuffer sb = new StringBuffer();

        sb.append("/**\n    ");
        sb.append(" * field: ");
        sb.append(introspectedColumn.getActualColumnName());

        String remarks = introspectedColumn.getRemarks();
        if (StringUtility.stringHasValue(remarks)) {
            sb.append("，");
            sb.append(remarks);
        }

        sb.append("\n     */");
        field.addJavaDocLine(sb.toString());
    }
}
