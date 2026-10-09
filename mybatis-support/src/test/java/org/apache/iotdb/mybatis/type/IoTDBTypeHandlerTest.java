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

package org.apache.iotdb.mybatis.type;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.LongTypeHandler;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IoTDBTypeHandlerTest {
  @Test
  public void preservesBinaryBytesAndUsesTheSupportedStreamOverload() throws Exception {
    byte[] bytes = {(byte) 0xff, 0, 39, (byte) 0x80};
    AtomicReference<byte[]> bound = new AtomicReference<>();
    PreparedStatement statement =
        proxy(
            PreparedStatement.class,
            (method, args) -> {
              assertEquals("setBinaryStream", method);
              assertEquals(Integer.valueOf(bytes.length), args[2]);
              bound.set(((InputStream) args[1]).readAllBytes());
              return null;
            });
    IoTDBBlobTypeHandler handler = new IoTDBBlobTypeHandler();
    handler.setParameter(statement, 1, bytes, JdbcType.BLOB);
    assertArrayEquals(bytes, bound.get());
    ResultSet results =
        proxy(
            ResultSet.class,
            (method, args) -> {
              assertEquals("getBytes", method);
              return bytes;
            });
    assertArrayEquals(bytes, handler.getResult(results, "payload"));
    assertArrayEquals(bytes, handler.getResult(results, 1));
  }

  @Test
  public void usesJdbcDateWithoutJdbc42GetObject() throws Exception {
    LocalDate value = LocalDate.of(2024, 2, 29);
    PreparedStatement statement =
        proxy(
            PreparedStatement.class,
            (method, args) -> {
              assertEquals("setDate", method);
              assertEquals(Date.valueOf(value), args[1]);
              return null;
            });
    IoTDBLocalDateTypeHandler handler = new IoTDBLocalDateTypeHandler();
    handler.setParameter(statement, 1, value, JdbcType.DATE);
    ResultSet results =
        proxy(
            ResultSet.class,
            (method, args) -> {
              if (method.equals("wasNull")) return false;
              assertEquals("getDate", method);
              return Date.valueOf(value);
            });
    assertEquals(value, handler.getResult(results, "reading_date"));
    assertEquals(value, handler.getResult(results, 1));
  }

  @Test
  public void preservesNullDatesAndBlobs() throws Exception {
    ResultSet results =
        proxy(
            ResultSet.class,
            (method, args) -> {
              if (method.equals("wasNull")) return true;
              if (method.equals("getDate")) return Date.valueOf("0002-11-30");
              return null;
            });
    assertNull(new IoTDBLocalDateTypeHandler().getResult(results, "reading_date"));
    assertNull(new IoTDBBlobTypeHandler().getResult(results, "payload"));
    for (JdbcType type : new JdbcType[] {JdbcType.DATE, JdbcType.BLOB}) {
      AtomicReference<Integer> boundType = new AtomicReference<>();
      PreparedStatement statement =
          proxy(
              PreparedStatement.class,
              (method, args) -> {
                assertEquals("setNull", method);
                boundType.set((Integer) args[1]);
                return null;
              });
      if (type == JdbcType.DATE) {
        new IoTDBLocalDateTypeHandler().setParameter(statement, 1, null, type);
      } else {
        new IoTDBBlobTypeHandler().setParameter(statement, 1, null, type);
      }
      assertEquals(Integer.valueOf(type.TYPE_CODE), boundType.get());
    }
  }

  @Test
  public void longHandlerPreservesRawMillisecondsMicrosecondsAndNanoseconds() throws Exception {
    for (long ticks : new long[] {1700000000123L, 1700000000123456L, 1700000000123456789L}) {
      PreparedStatement statement =
          proxy(
              PreparedStatement.class,
              (method, args) -> {
                assertEquals("setLong", method);
                assertEquals(ticks, args[1]);
                return null;
              });
      ResultSet results =
          proxy(
              ResultSet.class,
              (method, args) -> {
                if (method.equals("wasNull")) return false;
                assertEquals("getLong", method);
                return ticks;
              });
      LongTypeHandler handler = new LongTypeHandler();
      handler.setParameter(statement, 1, ticks, JdbcType.TIMESTAMP);
      assertEquals(Long.valueOf(ticks), handler.getResult(results, "time"));
    }
  }

  interface Call {
    Object invoke(String method, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Call call) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> call.invoke(method.getName(), args)));
  }
}
