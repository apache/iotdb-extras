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
package org.mybatis.generator.internal;

import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.dom.java.Field;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.JavaVisibility;
import org.mybatis.generator.api.dom.java.TopLevelClass;
import org.mybatis.generator.internal.util.StringUtility;

import java.util.Properties;

/**
 * 自定义注释生成
 */
public class SwaggerCommentGenerator extends DefaultCommentGenerator {
    private Properties properties;
    private boolean addRemarkComments = false;
    private static final String API_MODEL_FULL_CLASS_NAME = "io.swagger.v3.oas.annotations.media.Schema";

    /**
     * 设置用户配置的参数
     */
    @Override
    public void addConfigurationProperties(Properties properties) {
        super.addConfigurationProperties(properties);
        this.addRemarkComments = StringUtility.isTrue(properties.getProperty("addRemarkComments"));
        this.properties = new Properties();
        this.properties.putAll(properties);
    }

    /**
     * 给字段添加注释
     */
    @Override
    public void addFieldComment(Field field, IntrospectedTable introspectedTable, IntrospectedColumn introspectedColumn) {
        String remarks = introspectedColumn.getRemarks();
        //根据参数和备注信息判断是否添加备注信息
        if (addRemarkComments && StringUtility.stringHasValue(remarks)) {
            addFieldJavaDoc(field, introspectedColumn);
            //数据库中特殊字符需要转义
            if (remarks.contains("\"")) {
                remarks = remarks.replace("\"", "'");
            }
            //给model的字段添加swagger注解
            field.addJavaDocLine("@Schema(title = \"" + remarks + "\")");
        }
    }

    /**
     * 给model的字段添加注释
     */
    private void addFieldJavaDoc(Field field, IntrospectedColumn introspectedColumn) {

        StringBuffer sb = new StringBuffer();
        sb.append("/**\n    ");
        sb.append(" * Field: ");
        sb.append(introspectedColumn.getActualColumnName());
        String remarks = introspectedColumn.getRemarks();
        if (StringUtility.stringHasValue(remarks)) {
            sb.append("，");
            sb.append(remarks);
        }

        sb.append("\n     */");
        field.addJavaDocLine(sb.toString());
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

        sb.append(" The Class for table: ");
        sb.append(introspectedTable.getFullyQualifiedTable());
        topLevelClass.addJavaDocLine(sb.toString());
        topLevelClass.addJavaDocLine(" *");

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
        sb.append(" * Class serial version id\n    ");
        sb.append(" */");
        serialVersionUID.addJavaDocLine(sb.toString());

        topLevelClass.addField(serialVersionUID);
        topLevelClass.addImportedType(API_MODEL_FULL_CLASS_NAME);
        topLevelClass.addAnnotation("@Schema(title = \"" + introspectedTable.getFullyQualifiedTable() + "\", description = \"" + remarks + "\")");
    }

}
