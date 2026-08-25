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

package org.apache.iotdb.collector.plugin.builtin.source.mysql;

public class MySQLSourceConstant {

  public static final String MYSQL_SOURCE_DRIVER_KEY = "driver";
  public static final String MYSQL_SOURCE_DRIVER_DEFAULT_VALUE = "com.mysql.jdbc.cj.Driver";
  public static final String MYSQL_SOURCE_DRIVER_8_VALUE = "com.mysql.jdbc.cj.Driver";
  public static final String MYSQL_SOURCE_DRIVER_5_VALUE = "com.mysql.jdbc.Driver";

  public static final String MYSQL_USER_DEFAULT = "root";
  public static final String MYSQL_PASSWORD_DEFAULT = "";

  private MySQLSourceConstant() {
    throw new UnsupportedOperationException("Utility class");
  }
}
