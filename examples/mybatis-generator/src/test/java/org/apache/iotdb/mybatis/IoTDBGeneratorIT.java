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

package org.apache.iotdb.mybatis;

import org.apache.iotdb.mybatis.plugin.BatchInsertPlugin;
import org.apache.iotdb.mybatis.plugin.IoTDBKeyPlugin;
import org.apache.iotdb.mybatis.plugin.generator.resolver.IoTDBJavaTypeResolver;
import org.apache.iotdb.mybatis.plugin.mapper.MixMapper;
import org.apache.iotdb.mybatis.plugin.model.Mix;
import org.apache.iotdb.mybatis.type.IoTDBBlobTypeHandler;
import org.apache.iotdb.mybatis.type.IoTDBLocalDateTypeHandler;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.config.ColumnOverride;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.JDBCConnectionConfiguration;
import org.mybatis.generator.config.JavaClientGeneratorConfiguration;
import org.mybatis.generator.config.JavaModelGeneratorConfiguration;
import org.mybatis.generator.config.JavaTypeResolverConfiguration;
import org.mybatis.generator.config.ModelType;
import org.mybatis.generator.config.PluginConfiguration;
import org.mybatis.generator.config.SqlMapGeneratorConfiguration;
import org.mybatis.generator.config.TableConfiguration;
import org.mybatis.generator.internal.DefaultShellCallback;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Requires an explicitly selected iotdb-mybatis-it profile and an IoTDB 2.0.11 server. */
public class IoTDBGeneratorIT {
  @Rule public TemporaryFolder output = new TemporaryFolder();
  private final String database = "mbg_it_" + UUID.randomUUID().toString().replace("-", "");
  private final String url =
      System.getProperty("iotdb.it.url", "jdbc:iotdb://127.0.0.1:6667/?sql_dialect=table");
  private final String username = System.getProperty("iotdb.it.username", "root");
  private final String password = System.getProperty("iotdb.it.password", "root");
  private Connection admin;
  private boolean databaseCreated;
  private UnpooledDataSource source;

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

  @Before
  public void prepare() throws Exception {
    Class.forName("org.apache.iotdb.jdbc.IoTDBDriver");
    admin = DriverManager.getConnection(url, username, password);
    try (Statement sql = admin.createStatement()) {
      sql.execute("CREATE DATABASE " + database);
      databaseCreated = true;
      sql.execute("USE " + database);
      sql.execute(
          "CREATE TABLE mix (device_id STRING TAG, region STRING ATTRIBUTE, "
              + "plant_id STRING ATTRIBUTE, model_id STRING ATTRIBUTE, maintenance STRING ATTRIBUTE, "
              + "temperature FLOAT FIELD, humidity FLOAT FIELD, status BOOLEAN FIELD, "
              + "arrival_time TIMESTAMP FIELD, reading_date DATE FIELD, payload BLOB FIELD)");
      sql.execute(
          "CREATE TABLE readings (region STRING TAG, \"order\" STRING TAG, "
              + "temperature FLOAT FIELD, reading_date DATE FIELD, payload BLOB FIELD)");
    }
    source =
        new UnpooledDataSource(
            "org.apache.iotdb.jdbc.IoTDBDriver", databaseUrl(), username, password);
  }

  @After
  public void cleanup() throws Exception {
    if (admin != null) {
      try (Connection connection = admin;
          Statement sql = connection.createStatement()) {
        if (databaseCreated) sql.execute("DROP DATABASE " + database);
      }
    }
  }

  private SqlSessionFactory factory(InputStream xml, String resource) {
    org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
    config.setEnvironment(new Environment("it", new JdbcTransactionFactory(), source));
    config.addInterceptor(new IoTDBQueryInterceptor());
    new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
    return new SqlSessionFactoryBuilder().build(config);
  }

  @Test
  public void checkedInMapperRoundTripsRawTimeDateBlobAndNullKeys() throws Exception {
    try (InputStream xml =
            Resources.getResourceAsStream("org/apache/iotdb/mybatis/plugin/xml/MixMapper.xml");
        SqlSession session = factory(xml, "MixMapper.xml").openSession(true)) {
      MixMapper mapper = session.getMapper(MixMapper.class);
      Mix first = new Mix();
      first.setTime(timestamp());
      first.setDeviceId("d");
      first.setTemperature(1.5f);
      first.setArrivalTime(timestamp() + 1);
      first.setReadingDate(LocalDate.of(2024, 2, 29));
      first.setPayload(new byte[] {0, 39, 92, (byte) 128, (byte) 255});
      Mix nullTag = new Mix();
      nullTag.setTime(first.getTime());
      nullTag.setTemperature(2.5f);
      assertEquals(0, mapper.batchInsert(List.of()));
      mapper.batchInsert(List.of(first, nullTag));
      Mix actual = mapper.selectByPrimaryKey(first.getTime(), "d");
      assertEquals(first.getTime(), actual.getTime());
      assertEquals(first.getArrivalTime(), actual.getArrivalTime());
      assertEquals(first.getReadingDate(), actual.getReadingDate());
      assertArrayEquals(first.getPayload(), actual.getPayload());
      assertEquals(Float.valueOf(1.5f), actual.getTemperature());
      actual = mapper.selectByPrimaryKey(first.getTime(), null);
      assertEquals(Float.valueOf(2.5f), actual.getTemperature());
      assertNull(actual.getPayload());
      assertNull(actual.getReadingDate());
      mapper.deleteByPrimaryKey(first.getTime(), null);
      assertNull(mapper.selectByPrimaryKey(first.getTime(), null));
      assertNotNull(mapper.selectByPrimaryKey(first.getTime(), "d"));
    }
  }

  @Test
  public void actualMetadataGeneratesCompilableMappersAndChunkedInserts() throws Exception {
    Path generated = output.newFolder("generated").toPath();
    org.mybatis.generator.config.Configuration configuration =
        new org.mybatis.generator.config.Configuration();
    Context context = new Context(ModelType.FLAT);
    context.setId("iotdb");
    context.setTargetRuntime("MyBatis3Simple");
    context.addProperty("beginningDelimiter", "\"");
    context.addProperty("endingDelimiter", "\"");
    JDBCConnectionConfiguration jdbc = new JDBCConnectionConfiguration();
    jdbc.setDriverClass("org.apache.iotdb.jdbc.IoTDBDriver");
    jdbc.setConnectionURL(databaseUrl());
    jdbc.setUserId(username);
    jdbc.setPassword(password);
    context.setJdbcConnectionConfiguration(jdbc);
    JavaTypeResolverConfiguration resolver = new JavaTypeResolverConfiguration();
    resolver.setConfigurationType(IoTDBJavaTypeResolver.class.getName());
    resolver.addProperty("jdbcType.FLOAT", "java.lang.Float");
    context.setJavaTypeResolverConfiguration(resolver);
    for (Class<?> plugin :
        List.of(
            BatchInsertPlugin.class,
            IoTDBKeyPlugin.class,
            org.mybatis.generator.plugins.UnmergeableXmlMappersPlugin.class,
            org.mybatis.generator.plugins.VirtualPrimaryKeyPlugin.class)) {
      PluginConfiguration pc = new PluginConfiguration();
      pc.setConfigurationType(plugin.getName());
      pc.addProperty("batchSize", "2");
      context.addPluginConfiguration(pc);
    }
    JavaModelGeneratorConfiguration models = new JavaModelGeneratorConfiguration();
    models.setTargetProject(generated.toString());
    models.setTargetPackage("generated");
    context.setJavaModelGeneratorConfiguration(models);
    JavaClientGeneratorConfiguration clients = new JavaClientGeneratorConfiguration();
    clients.setTargetProject(generated.toString());
    clients.setTargetPackage("generated");
    clients.setConfigurationType("XMLMAPPER");
    context.setJavaClientGeneratorConfiguration(clients);
    SqlMapGeneratorConfiguration mappings = new SqlMapGeneratorConfiguration();
    mappings.setTargetProject(generated.toString());
    mappings.setTargetPackage("generated");
    context.setSqlMapGeneratorConfiguration(mappings);
    TableConfiguration table = new TableConfiguration(context);
    table.setSchema(database);
    table.setTableName("readings");
    table.setDomainObjectName("Reading");
    table.addProperty("virtualKeyColumns", "time,region,order");
    table.setDelimitIdentifiers(true);
    table.setAllColumnDelimitingEnabled(true);
    table.setUpdateByExampleStatementEnabled(false);
    table.setUpdateByPrimaryKeyStatementEnabled(false);
    ColumnOverride date = new ColumnOverride("reading_date");
    date.setJavaType("java.time.LocalDate");
    date.setTypeHandler(IoTDBLocalDateTypeHandler.class.getName());
    table.addColumnOverride(date);
    ColumnOverride blob = new ColumnOverride("payload");
    blob.setJavaType("byte[]");
    blob.setTypeHandler(IoTDBBlobTypeHandler.class.getName());
    table.addColumnOverride(blob);
    context.addTableConfiguration(table);
    configuration.addContext(context);
    List<String> warnings = new ArrayList<>();
    new MyBatisGenerator(configuration, new DefaultShellCallback(true), warnings).generate(null);
    assertTrue(warnings.toString(), warnings.isEmpty());

    Path classes = output.newFolder("classes").toPath();
    List<String> args =
        new ArrayList<>(
            List.of(
                "-proc:none",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString()));
    try (java.util.stream.Stream<Path> files = Files.walk(generated)) {
      args.addAll(
          files
              .filter(p -> p.toString().endsWith(".java"))
              .map(Path::toString)
              .collect(Collectors.toList()));
    }
    assertEquals(
        0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])));
    ClassLoader previous = Resources.getDefaultClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      Resources.setDefaultClassLoader(loader);
      Class<?> entity = loader.loadClass("generated.Reading");
      Class<?> mapperType = loader.loadClass("generated.ReadingMapper");
      try (InputStream xml =
              Files.newInputStream(generated.resolve("generated/ReadingMapper.xml"));
          SqlSession session = factory(xml, "ReadingMapper.xml").openSession(true)) {
        org.junit.Assert.assertFalse(
            session.getConfiguration().hasStatement("generated.ReadingMapper.updateByPrimaryKey"));
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
          Object row = entity.getConstructor().newInstance();
          entity.getMethod("setTime", Long.class).invoke(row, timestamp() + i);
          entity.getMethod("setOrder", String.class).invoke(row, "o");
          entity.getMethod("setTemperature", Float.class).invoke(row, 12.5f);
          entity
              .getMethod("setReadingDate", LocalDate.class)
              .invoke(row, LocalDate.of(2024, 2, 29));
          entity
              .getMethod("setPayload", byte[].class)
              .invoke(row, (Object) new byte[] {0, (byte) 255});
          rows.add(row);
        }
        Object mapper = session.getMapper(mapperType);
        mapperType.getMethod("batchInsert", List.class).invoke(mapper, rows);
        Map<String, Object> key = new HashMap<>();
        key.put("time", timestamp());
        key.put("region", null);
        key.put("order", "o");
        Object actual = session.selectOne("generated.ReadingMapper.selectByPrimaryKey", key);
        assertNotNull(actual);
        assertEquals(timestamp(), entity.getMethod("getTime").invoke(actual));
        assertEquals(LocalDate.of(2024, 2, 29), entity.getMethod("getReadingDate").invoke(actual));
        assertArrayEquals(
            new byte[] {0, (byte) 255}, (byte[]) entity.getMethod("getPayload").invoke(actual));
        assertEquals(5, session.selectList("generated.ReadingMapper.selectAll").size());
        session.delete("generated.ReadingMapper.deleteByPrimaryKey", key);
        assertNull(session.selectOne("generated.ReadingMapper.selectByPrimaryKey", key));
        assertEquals(4, session.selectList("generated.ReadingMapper.selectAll").size());
      }
    } finally {
      Resources.setDefaultClassLoader(previous);
    }
  }
}
