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

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {
  @TempDir Path output;
  static final List<CodeGenerator.Column> COLUMNS =
      List.of(
          new CodeGenerator.Column("time", "TIMESTAMP", "TIME"),
          new CodeGenerator.Column("region", "STRING", "TAG"),
          new CodeGenerator.Column("device_id", "STRING", "TAG"),
          new CodeGenerator.Column("description", "STRING", "ATTRIBUTE"),
          new CodeGenerator.Column("temperature", "FLOAT", "FIELD"),
          new CodeGenerator.Column("reading_date", "DATE", "FIELD"),
          new CodeGenerator.Column("payload", "BLOB", "FIELD"));

  static String render(String template, Map<String, Object> data) {
    Properties properties = new Properties();
    properties.setProperty("resource.loaders", "class");
    properties.setProperty(
        "resource.loader.class.class",
        "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
    VelocityEngine engine = new VelocityEngine(properties);
    engine.init();
    StringWriter output = new StringWriter();
    engine.getTemplate("templates/" + template, "UTF-8").merge(new VelocityContext(data), output);
    return output.toString();
  }

  @Test
  void rendersAndCompilesCompositeKeyTemplatesWithDateAndBlobMappings() throws Exception {
    Map<String, Object> data = new LinkedHashMap<>(CodeGenerator.templateData(COLUMNS));
    data.put("entity", "GeneratedRow");
    data.put("table", Map.of("mapperName", "GeneratedRowMapper"));
    data.put("package", Map.of("Entity", "generated", "Mapper", "generated"));
    data.put("iotdbTable", "&quot;readings&quot;");
    data.put("iotdbJavaTable", "\"\\\"readings\\\"\"");
    String entity = render("iotdb-entity.java.vm", data);
    String mapper = render("iotdb-mapper.java.vm", data);
    String xml = render("iotdb-mapper.xml.vm", data);
    assertThat(entity)
        .contains("Long time", "java.time.LocalDate readingDate", "byte[] payload")
        .doesNotContain("@TableId", "@MppMultiId");
    assertThat(mapper).contains("IoTDBTableMapper<GeneratedRow>");
    assertThat(xml)
        .contains(
            "key.time",
            "key.region",
            "key.deviceId",
            "IS NULL",
            "IoTDBBlobTypeHandler",
            "IoTDBLocalDateTypeHandler",
            "INSERT INTO",
            "updateAttributes");
    Files.writeString(output.resolve("GeneratedRow.java"), entity);
    Files.writeString(output.resolve("GeneratedRowMapper.java"), mapper);
    assertThat(
            ToolProvider.getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    output.toString(),
                    output.resolve("GeneratedRow.java").toString(),
                    output.resolve("GeneratedRowMapper.java").toString()))
        .isZero();
  }

  @Test
  void quotesReservedIdentifiersAndRejectsMissingTimeMetadata() {
    Map<String, Object> data =
        CodeGenerator.templateData(
            List.of(
                new CodeGenerator.Column("time", "TIMESTAMP", "TIME"),
                new CodeGenerator.Column("order", "STRING", "TAG")));
    assertThat(data.get("iotdbColumns")).isEqualTo("&quot;time&quot;, &quot;order&quot;");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> CodeGenerator.templateData(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(data.get("iotdbHasAttributes")).isEqualTo(false);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                CodeGenerator.templateData(
                    List.of(
                        new CodeGenerator.Column("time", "TIMESTAMP", "TIME"),
                        new CodeGenerator.Column("class", "STRING", "TAG"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
