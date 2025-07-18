/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.flink.options;

import java.io.Serializable;
import java.util.List;

public class IoTDBOptions implements Serializable {

  protected String host;
  protected int port;
  protected String user;
  protected String password;
  protected List<String> nodeUrls;

  public IoTDBOptions(String host, int port, String user, String password) {
    this.host = host;
    this.port = port;
    this.user = user;
    this.password = password;
  }

  public IoTDBOptions(List<String> nodeUrls, String user, String password) {
    this.nodeUrls = nodeUrls;
    this.user = user;
    this.password = password;
  }

  public IoTDBOptions() {}

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public List<String> getNodeUrls() {
    return nodeUrls;
  }

  public void setNodeUrls(List<String> nodeUrls) {
    this.nodeUrls = nodeUrls;
  }
}
