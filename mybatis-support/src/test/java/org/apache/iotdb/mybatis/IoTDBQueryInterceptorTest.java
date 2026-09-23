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

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IoTDBQueryInterceptorTest {
  @Test
  public void executesQueryOnceAndRetainsResultUntilMyBatisClosesStatement() throws Exception {
    AtomicInteger queries = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    ResultSet result =
        (ResultSet)
            Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ResultSet.class}, (p, m, a) -> null);
    PreparedStatement driver =
        (PreparedStatement)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (p, m, a) -> {
                  if ("executeQuery".equals(m.getName())) {
                    queries.incrementAndGet();
                    return result;
                  }
                  if ("close".equals(m.getName())) {
                    closes.incrementAndGet();
                    return null;
                  }
                  if ("getResultSet".equals(m.getName())) return null;
                  throw new AssertionError("Unexpected JDBC call: " + m.getName());
                });
    PreparedStatement statement = IoTDBQueryInterceptor.queryStatement(driver);
    assertTrue(statement.execute());
    assertSame(result, statement.getResultSet());
    assertSame(result, statement.getResultSet());
    assertEquals(1, queries.get());
    assertEquals(0, closes.get());
    statement.close();
    assertEquals(1, closes.get());
  }

  @Test
  public void preservesSqlExceptionsWithoutRetrying() {
    SQLException expected = new SQLException("query rejected");
    AtomicInteger queries = new AtomicInteger();
    PreparedStatement driver =
        (PreparedStatement)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (p, m, a) -> {
                  queries.incrementAndGet();
                  throw expected;
                });
    PreparedStatement statement = IoTDBQueryInterceptor.queryStatement(driver);
    assertSame(expected, assertThrows(SQLException.class, statement::execute));
    assertEquals(1, queries.get());
  }
}
