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

import org.apache.iotdb.mybatis.plugin.mapper.MixMapper;
import org.apache.iotdb.mybatis.type.IoTDBBlobTypeHandler;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MapperConfigurationTest {
  @Test
  public void packagedConfigurationParsesAndUsesNullableCompositeKey() throws Exception {
    try (InputStream input = Resources.getResourceAsStream("mybatis-config.xml")) {
      SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(input);
      assertTrue(
          factory.getConfiguration().getInterceptors().stream()
              .anyMatch(IoTDBQueryInterceptor.class::isInstance));
      Map<String, Object> key = new HashMap<>();
      key.put("time", 1700000000123456789L);
      key.put("deviceId", null);
      for (String operation : new String[] {"selectByPrimaryKey", "deleteByPrimaryKey"}) {
        BoundSql sql =
            factory
                .getConfiguration()
                .getMappedStatement(MixMapper.class.getName() + "." + operation)
                .getBoundSql(key);
        assertTrue(sql.getSql().contains("\"device_id\" IS NULL"));
        assertEquals(1, sql.getParameterMappings().size());
      }
      assertTrue(
          factory
              .getConfiguration()
              .getResultMap(MixMapper.class.getName() + ".BaseResultMap")
              .getResultMappings()
              .stream()
              .anyMatch(mapping -> mapping.getTypeHandler() instanceof IoTDBBlobTypeHandler));
    }
  }
}
