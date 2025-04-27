/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb;

import org.apache.iotdb.jdbc.IoTDBDataSource;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.DataSourceConfig;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DateType;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;

import java.sql.Types;
import java.util.Collections;

public class Main {
  public static void main(String[] args) {
    IoTDBDataSource dataSource = new IoTDBDataSource();
    dataSource.setUrl("jdbc:iotdb://127.0.0.1:6667/test?sql_dialect=table");
    dataSource.setUser("root");
    dataSource.setPassword("root");
    FastAutoGenerator generator =
        FastAutoGenerator.create(
            new DataSourceConfig.Builder(dataSource)
                .driverClassName("org.apache.iotdb.jdbc.IoTDBDriver"));
    generator
        .globalConfig(
            builder -> {
              builder
                  .author("IoTDB")
                  .enableSwagger()
                  .dateType(DateType.ONLY_DATE)
                  .outputDir("/apache/iotdb-extras/examples/mybatisplus-generator/src/main/java/");
            })
        .packageConfig(
            builder -> {
              builder
                  .parent("org.apache.iotdb")
                  .mapper("mapper")
                  .pathInfo(
                      Collections.singletonMap(
                          OutputFile.xml,
                          "/apache/iotdb-extras/examples/mybatisplus-generator/src/main/java/"));
            })
        .dataSourceConfig(
            builder -> {
              builder.typeConvertHandler(
                  (globalConfig, typeRegistry, metaInfo) -> {
                    int typeCode = metaInfo.getJdbcType().TYPE_CODE;
                    switch (typeCode) {
                      case Types.FLOAT:
                        return DbColumnType.FLOAT;
                      default:
                        return typeRegistry.getColumnType(metaInfo);
                    }
                  });
            })
        .strategyConfig(
            builder -> {
              builder.addInclude("mix");
              builder
                  .entityBuilder()
                  .enableLombok()
                  .addIgnoreColumns("create_time")
                  .enableFileOverride();
              builder
                  .serviceBuilder()
                  .formatServiceFileName("%sService")
                  .formatServiceImplFileName("%sServiceImpl")
                  .convertServiceFileName((entityName -> entityName + "Service"))
                  .enableFileOverride();
              builder.controllerBuilder().enableRestStyle().enableFileOverride();
            })
        .execute();
  }
}
