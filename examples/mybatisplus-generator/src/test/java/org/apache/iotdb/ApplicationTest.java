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

import org.apache.iotdb.entity.Table1;
import org.apache.iotdb.mapper.Table1Mapper;
import org.apache.iotdb.mapper.Table2Mapper;
import org.apache.iotdb.mybatis.type.IoTDBBlobTypeHandler;
import org.apache.iotdb.mybatis.type.IoTDBLocalDateTypeHandler;
import org.apache.iotdb.service.Table1Service;
import org.apache.iotdb.service.impl.Table1ServiceImpl;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.hikari.initialization-fail-timeout=-1",
      "spring.datasource.hikari.minimum-idle=0"
    })
public class ApplicationTest {
  @Autowired private SqlSessionFactory factory;
  @Autowired private Table1Service service;

  @Test
  void registersSafeStatementsAndPackagedXmlForBothTables() {
    for (Class<?> mapper : List.of(Table1Mapper.class, Table2Mapper.class)) {
      for (String method :
          List.of(
              "insert",
              "selectList",
              "selectByKey",
              "deleteByKey",
              "upsertFields",
              "updateAttributes")) {
        assertThat(factory.getConfiguration().hasStatement(mapper.getName() + "." + method))
            .isTrue();
      }
      for (String method : List.of("selectById", "deleteById", "updateById", "update")) {
        assertThat(factory.getConfiguration().hasStatement(mapper.getName() + "." + method))
            .isFalse();
        assertThat(Arrays.stream(mapper.getMethods()).noneMatch(m -> m.getName().equals(method)))
            .isTrue();
      }
    }
  }

  private BoundSql sql(String method, Object parameters) {
    return factory
        .getConfiguration()
        .getMappedStatement(Table1Mapper.class.getName() + "." + method)
        .getBoundSql(parameters);
  }

  private Table1 row() {
    Table1 row = new Table1();
    row.setTime(1700000000123456789L);
    row.setRegion("r");
    row.setPlantId("p");
    row.setDeviceId("d");
    return row;
  }

  @Test
  void keysUseTimeAndEveryTagIncludingNull() {
    Table1 key = row();
    for (String method : List.of("selectByKey", "deleteByKey")) {
      BoundSql query = sql(method, Map.of("key", key));
      assertThat(query.getSql())
          .contains("\"time\" = ?", "\"region\" = ?", "\"plant_id\" = ?", "\"device_id\" = ?");
      assertThat(query.getParameterMappings()).hasSize(4);
      key.setRegion(null);
      assertThat(sql(method, Map.of("key", key)).getSql()).contains("\"region\" IS NULL");
      key.setRegion("r");
    }
  }

  @Test
  void fieldsUseInsertWhileAttributesUseDevicePredicates() {
    Table1 row = row();
    row.setTemperature(42.5f);
    row.setReadingDate(LocalDate.of(2024, 2, 29));
    row.setPayload(new byte[] {0, (byte) 255});
    BoundSql insert = sql("upsertFields", Map.of("row", row));
    assertThat(insert.getSql())
        .contains("INSERT INTO", "\"temperature\"", "\"reading_date\"", "\"payload\"")
        .doesNotContain("UPDATE", "\"maintenance\"", "\"model_id\"", "\"humidity\"");
    assertThat(
            insert.getParameterMappings().stream()
                .map(m -> m.getTypeHandler().getClass().getName()))
        .contains(IoTDBBlobTypeHandler.class.getName(), IoTDBLocalDateTypeHandler.class.getName());
    BoundSql update = sql("updateAttributes", Map.of("row", row));
    assertThat(update.getSql())
        .contains("UPDATE", "\"model_id\" = ?", "\"maintenance\" = ?")
        .doesNotContain("\"time\"", "\"temperature\"");
    assertThat(update.getParameterMappings()).hasSize(5);
  }

  @Test
  void rejectsMissingTimeAndEmptyFieldPatchBeforeAccessingDatabase() {
    assertThatThrownBy(() -> service.selectByKey(new Table1()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.deleteByKey(new Table1()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.upsertFields(row()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fieldWriteCallsTheInsertStatement() {
    AtomicInteger calls = new AtomicInteger();
    Table1Mapper mapper =
        (Table1Mapper)
            Proxy.newProxyInstance(
                Table1Mapper.class.getClassLoader(),
                new Class<?>[] {Table1Mapper.class},
                (proxy, method, args) -> {
                  assertThat(method.getName()).isEqualTo("upsertFields");
                  calls.incrementAndGet();
                  return 1;
                });
    Table1 row = row();
    row.setTemperature(3.5f);
    assertThat(new Table1ServiceImpl(mapper).upsertFields(row)).isEqualTo(1);
    assertThat(calls).hasValue(1);
  }
}
