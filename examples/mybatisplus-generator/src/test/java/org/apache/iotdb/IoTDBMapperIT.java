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

import org.apache.iotdb.config.IoTDBMybatisConfiguration;
import org.apache.iotdb.entity.Table1;
import org.apache.iotdb.jdbc.IoTDBDataSource;
import org.apache.iotdb.mybatis.IoTDBQueryInterceptor;
import org.apache.iotdb.service.Table1Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.tools.ToolProvider;

import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt in with -Piotdb-mybatis-it; a missing server is a failure, never a skipped test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IoTDBMapperIT {
  @TempDir Path output;
  private final String database = "mp_it_" + UUID.randomUUID().toString().replace("-", "");
  private final String url =
      System.getProperty("iotdb.it.url", "jdbc:iotdb://127.0.0.1:6667/?sql_dialect=table");
  private final String username = System.getProperty("iotdb.it.username", "root");
  private final String password = System.getProperty("iotdb.it.password", "root");
  private Connection admin;
  private ConfigurableApplicationContext context;
  private Table1Service service;
  private boolean databaseCreated;

  private String databaseUrl() {
    int start = url.indexOf('/', "jdbc:iotdb://".length());
    int query = url.indexOf('?', start);
    if (start < 0 || query < 0 || !url.contains("sql_dialect=table")) {
      throw new IllegalArgumentException("iotdb.it.url must include /?sql_dialect=table");
    }
    return url.substring(0, start + 1) + database + url.substring(query);
  }

  private long timestamp() {
    switch (System.getProperty("iotdb.it.precision", "ms")) {
      case "ms":
        return 1700000000123L;
      case "us":
        return 1700000000123456L;
      case "ns":
        return 1700000000123456789L;
      default:
        throw new IllegalArgumentException("iotdb.it.precision must be ms, us or ns");
    }
  }

  @BeforeAll
  void startApplicationAgainstIsolatedDatabase() throws Exception {
    Class.forName("org.apache.iotdb.jdbc.IoTDBDriver");
    admin = DriverManager.getConnection(url, username, password);
    try (Statement sql = admin.createStatement()) {
      sql.execute("CREATE DATABASE " + database);
      databaseCreated = true;
      sql.execute("USE " + database);
      for (String table : List.of("table1", "table2")) {
        sql.execute(
            "CREATE TABLE "
                + table
                + " (region STRING TAG, plant_id STRING TAG, "
                + "device_id STRING TAG, model_id STRING ATTRIBUTE, maintenance STRING ATTRIBUTE, "
                + "temperature FLOAT FIELD, humidity FLOAT FIELD, status BOOLEAN FIELD, "
                + "arrival_time TIMESTAMP FIELD, reading_date DATE FIELD, payload BLOB FIELD)");
      }
      sql.execute(
          "CREATE TABLE readings (region STRING TAG, \"order\" STRING TAG, "
              + "description STRING ATTRIBUTE, temperature FLOAT FIELD, reading_date DATE FIELD, "
              + "payload BLOB FIELD)");
    }
    context =
        new SpringApplicationBuilder(Main.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + databaseUrl(),
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.datasource.hikari.minimum-idle=0");
    service = context.getBean(Table1Service.class);
  }

  @AfterAll
  void cleanup() throws Exception {
    if (context != null) context.close();
    if (admin != null) {
      try (Connection connection = admin;
          Statement sql = connection.createStatement()) {
        if (databaseCreated) sql.execute("DROP DATABASE " + database);
      }
    }
  }

  private Table1 row(long time, String region) {
    Table1 row = new Table1();
    row.setTime(time);
    row.setRegion(region);
    row.setPlantId("p");
    row.setDeviceId("d");
    row.setTemperature(21.5f);
    row.setHumidity(60.0f);
    row.setArrivalTime(time + 7);
    row.setReadingDate(LocalDate.of(2024, 2, 29));
    row.setPayload(new byte[] {0, 1, 39, 92, (byte) 128, (byte) 255});
    row.setModelId("initial");
    row.setMaintenance("yes");
    return row;
  }

  @Test
  void compositeKeysFieldPatchesAttributesAndTypesRoundTrip() {
    Table1 first = row(timestamp(), "r1");
    Table1 otherDevice = row(timestamp(), "r2");
    Table1 later = row(timestamp() + 1, "r1");
    Table1 nullTag = row(timestamp(), null);
    nullTag.setReadingDate(null);
    nullTag.setPayload(null);
    for (Table1 row : List.of(first, otherDevice, later, nullTag)) service.insert(row);
    Table1 actual = service.selectByKey(first);
    assertThat(actual.getTime()).isEqualTo(first.getTime());
    assertThat(actual.getArrivalTime()).isEqualTo(first.getArrivalTime());
    assertThat(actual.getReadingDate()).isEqualTo(first.getReadingDate());
    assertThat(actual.getPayload()).containsExactly(first.getPayload());

    Table1 patch = new Table1();
    patch.setTime(first.getTime());
    patch.setRegion(first.getRegion());
    patch.setPlantId(first.getPlantId());
    patch.setDeviceId(first.getDeviceId());
    patch.setTemperature(99.5f);
    service.upsertFields(patch);
    actual = service.selectByKey(first);
    assertThat(actual.getTemperature()).isEqualTo(99.5f);
    assertThat(actual.getHumidity()).isEqualTo(60.0f);
    assertThat(actual.getPayload()).containsExactly(first.getPayload());
    assertThat(service.selectByKey(otherDevice).getTemperature()).isEqualTo(21.5f);

    patch.setModelId("updated");
    patch.setMaintenance(null);
    service.updateAttributes(patch);
    for (Table1 key : List.of(first, later)) {
      assertThat(service.selectByKey(key).getModelId()).isEqualTo("updated");
      assertThat(service.selectByKey(key).getMaintenance()).isNull();
    }
    assertThat(service.selectByKey(otherDevice).getModelId()).isEqualTo("initial");
    assertThat(service.selectByKey(nullTag).getReadingDate()).isNull();
    assertThat(service.selectByKey(nullTag).getPayload()).isNull();
    assertThat(service.list()).hasSize(4); // MP's injected selectList also uses the handlers.
    service.deleteByKey(nullTag);
    assertThat(service.selectByKey(nullTag)).isNull();
    assertThat(service.selectByKey(first)).isNotNull();
  }

  @Test
  void actualMetadataGeneratesCompilableExecutableMappers() throws Exception {
    IoTDBDataSource source = new IoTDBDataSource();
    source.setUrl(databaseUrl());
    source.setUser(username);
    source.setPassword(password);
    CodeGenerator.generate(source, database, List.of("readings"), output);
    Path classes = Files.createDirectories(output.resolve("classes"));
    List<String> args =
        new ArrayList<>(
            List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
    try (java.util.stream.Stream<Path> paths = Files.walk(output.resolve("java"))) {
      args.addAll(
          paths
              .filter(p -> p.toString().endsWith(".java"))
              .map(Path::toString)
              .collect(Collectors.toList()));
    }
    assertThat(args).hasSizeGreaterThan(4);
    assertThat(
            ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])))
        .isZero();

    ClassLoader previous = Resources.getDefaultClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      Resources.setDefaultClassLoader(loader);
      Class<?> entity = loader.loadClass("org.apache.iotdb.entity.Readings");
      Class<?> mapper = loader.loadClass("org.apache.iotdb.mapper.ReadingsMapper");
      MybatisConfiguration config = new MybatisConfiguration();
      config.setEnvironment(new Environment("it", new JdbcTransactionFactory(), source));
      config.addInterceptor(new IoTDBQueryInterceptor());
      GlobalConfigUtils.setGlobalConfig(
          config,
          new GlobalConfig()
              .setDbConfig(new GlobalConfig.DbConfig())
              .setSqlInjector(new IoTDBMybatisConfiguration().ioTDBSqlInjector()));
      try (InputStream xml =
          Files.newInputStream(output.resolve("resources/mappers/ReadingsMapper.xml"))) {
        new XMLMapperBuilder(xml, config, "ReadingsMapper.xml", config.getSqlFragments()).parse();
      }
      try (SqlSession session =
          new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
        Object instance = session.getMapper(mapper);
        Object row = entity.getConstructor().newInstance();
        entity.getMethod("setTime", Long.class).invoke(row, timestamp() + 10);
        entity.getMethod("setOrder", String.class).invoke(row, "quoted");
        entity.getMethod("setTemperature", Float.class).invoke(row, 12.5f);
        entity.getMethod("setReadingDate", LocalDate.class).invoke(row, LocalDate.of(2024, 2, 29));
        byte[] payload = new byte[] {0, 39, (byte) 255};
        entity.getMethod("setPayload", byte[].class).invoke(row, (Object) payload);
        mapper.getMethod("insert", Object.class).invoke(instance, row);
        Object actual = mapper.getMethod("selectByKey", Object.class).invoke(instance, row);
        assertThat(entity.getMethod("getTime").invoke(actual)).isEqualTo(timestamp() + 10);
        assertThat((byte[]) entity.getMethod("getPayload").invoke(actual)).containsExactly(payload);
        assertThat(entity.getMethod("getReadingDate").invoke(actual))
            .isEqualTo(LocalDate.of(2024, 2, 29));
        entity.getMethod("setTemperature", Float.class).invoke(row, 44.5f);
        mapper.getMethod("upsertFields", Object.class).invoke(instance, row);
        entity.getMethod("setDescription", String.class).invoke(row, "generated");
        mapper.getMethod("updateAttributes", Object.class).invoke(instance, row);
        actual = mapper.getMethod("selectByKey", Object.class).invoke(instance, row);
        assertThat(entity.getMethod("getTemperature").invoke(actual)).isEqualTo(44.5f);
        assertThat(entity.getMethod("getDescription").invoke(actual)).isEqualTo("generated");
        mapper.getMethod("deleteByKey", Object.class).invoke(instance, row);
        assertThat(mapper.getMethod("selectByKey", Object.class).invoke(instance, row)).isNull();
      }
    } finally {
      Resources.setDefaultClassLoader(previous);
    }
  }
}
