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

package org.apache.iotdb.protocol.mqtt;

import io.moquette.broker.security.IAuthenticator;
import org.apache.iotdb.isession.ISession;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.session.Session;
import org.apache.tsfile.external.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The MQTT broker authenticator. */
public class BrokerAuthenticator implements IAuthenticator {
  private static final Logger LOG = LoggerFactory.getLogger(BrokerAuthenticator.class);

  private final MqttConfig config;

  public BrokerAuthenticator(MqttConfig config) {
    this.config = config;
  }

  @Override
  public boolean checkValid(String clientId, String username, byte[] password) {
    if (StringUtils.isBlank(username) || password == null) {
      return false;
    }

    // validate by logging in IoTDB
    try (ISession ignored = new Session(config.getIotdbHost(), config.getIotdbPort(),
        username, new String(password))) {
      return true;
    } catch (IoTDBConnectionException e) {
      LOG.warn("Failed to connect to IoTDB server.", e);
      return false;
    }
  }
}
