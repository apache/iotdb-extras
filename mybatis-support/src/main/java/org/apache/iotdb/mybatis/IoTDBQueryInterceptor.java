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

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Uses executeQuery for MyBatis prepared queries. IoTDB JDBC 2.0.11 table statements return true
 * from execute() without assigning their ResultSet. Register on the IoTDB SqlSessionFactory.
 */
@Intercepts({
  @Signature(
      type = StatementHandler.class,
      method = "query",
      args = {Statement.class, ResultHandler.class}),
  @Signature(
      type = StatementHandler.class,
      method = "queryCursor",
      args = {Statement.class})
})
public class IoTDBQueryInterceptor implements Interceptor {
  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object statement = invocation.getArgs()[0];
    if (!(statement instanceof PreparedStatement)) return invocation.proceed();
    invocation.getArgs()[0] = queryStatement((PreparedStatement) statement);
    try {
      return invocation.proceed();
    } finally {
      invocation.getArgs()[0] = statement;
    }
  }

  static PreparedStatement queryStatement(PreparedStatement delegate) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            new java.lang.reflect.InvocationHandler() {
              private ResultSet resultSet;

              @Override
              public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                  throws Throwable {
                if ("execute".equals(method.getName()) && (args == null || args.length == 0)) {
                  resultSet = delegate.executeQuery();
                  return resultSet != null;
                }
                if ("getResultSet".equals(method.getName())) return resultSet;
                try {
                  return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              }
            });
  }
}
