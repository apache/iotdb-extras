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

package org.apache.iotdb.collector.plugin.builtin.source.constant;

public class IoTDBPushSourceConstant {
  public static final String HOST_KEY = "host";
  public static final String PORT_KEY = "port";
  public static final String TOPIC_KEY = "topic";
  public static final String TIMEOUT_KEY = "timeout";
  public static final String DEVICE_ID_KEY = "deviceId";

  public static final String HOST_VALUE = "127.0.0.1";
  public static final Integer PORT_VALUE = 6668;
  public static final String TOPIC_VALUE = "root_all";
  public static final Long TIMEOUT_VALUE = 10000L;
  public static final String DEVICE_ID_VALUE = "root.test.demo";
}
