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

package org.apache.iotdb.protocol.mqtt;

import static org.apache.iotdb.protocol.mqtt.MqttConstant.MQTT_FOLDER_NAME;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.conf.TrimProperties;
import org.apache.iotdb.protocol.mqtt.msg.PayloadFormatManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MqttConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(MqttConfig.class);
  private static final String DEFAULT_CONFIG_FILE_PATH = "mqtt.properties";

  /** The mqtt service binding host. */
  private String mqttHost = "127.0.0.1";

  /** The mqtt service binding port. */
  private int mqttPort = 1883;

  /** The target IoTDB binding host. */
  private String iotdbHost = "127.0.0.1";

  /** The target IoTDB binding port. */
  private int iotdbPort = 6667;

  /** The handler pool size for handing the mqtt messages. */
  private int mqttHandlerPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() >> 1);

  /** The mqtt message payload formatter. */
  private String mqttPayloadFormatter = "json";

  /** The mqtt save data path */
  private String mqttDataPath = "data/";

  /** Max mqtt message size. Unit: byte */
  private int mqttMaxMessageSize = 1048576;

  /** Trigger MQTT forward pool size */
  private int triggerForwardMQTTPoolSize = 4;

  /** External lib directory for MQTT, stores user-uploaded JAR files */
  private String mqttDir =
      IoTDBConstant.EXT_FOLDER_NAME + File.separator + MQTT_FOLDER_NAME;

  private PayloadFormatManager payloadFormatManager;

  private void init() {
    this.payloadFormatManager = new PayloadFormatManager(this);
  }

  public PayloadFormatManager getPayloadFormatManager() {
    return payloadFormatManager;
  }

  public String getMqttHost() {
    return mqttHost;
  }

  public void setMqttHost(String mqttHost) {
    this.mqttHost = mqttHost;
  }

  public int getMqttPort() {
    return mqttPort;
  }

  public void setMqttPort(int mqttPort) {
    this.mqttPort = mqttPort;
  }

  public int getMqttHandlerPoolSize() {
    return mqttHandlerPoolSize;
  }

  public void setMqttHandlerPoolSize(int mqttHandlerPoolSize) {
    this.mqttHandlerPoolSize = mqttHandlerPoolSize;
  }

  public String getMqttPayloadFormatter() {
    return mqttPayloadFormatter;
  }

  public void setMqttPayloadFormatter(String mqttPayloadFormatter) {
    this.mqttPayloadFormatter = mqttPayloadFormatter;
  }

  public String getMqttDataPath() {
    return mqttDataPath;
  }

  public void setMqttDataPath(String mqttDataPath) {
    this.mqttDataPath = mqttDataPath;
  }

  public int getMqttMaxMessageSize() {
    return mqttMaxMessageSize;
  }

  public void setMqttMaxMessageSize(int mqttMaxMessageSize) {
    this.mqttMaxMessageSize = mqttMaxMessageSize;
  }

  public int getTriggerForwardMQTTPoolSize() {
    return triggerForwardMQTTPoolSize;
  }

  public void setTriggerForwardMQTTPoolSize(int triggerForwardMQTTPoolSize) {
    this.triggerForwardMQTTPoolSize = triggerForwardMQTTPoolSize;
  }

  public String getMqttDir() {
    return mqttDir;
  }

  public void setMqttDir(String mqttDir) {
    this.mqttDir = mqttDir;
  }

  public String getIotdbHost() {
    return iotdbHost;
  }

  public void setIotdbHost(String iotdbHost) {
    this.iotdbHost = iotdbHost;
  }

  public int getIotdbPort() {
    return iotdbPort;
  }

  public void setIotdbPort(int iotdbPort) {
    this.iotdbPort = iotdbPort;
  }

  public static MqttConfig fromFile(String configFilePath) {
    TrimProperties commonProperties = new TrimProperties();
    if (configFilePath == null) {
      LOGGER.warn("configFilePath not set, use the default config file.");
      configFilePath = DEFAULT_CONFIG_FILE_PATH;
    }

    try (InputStream inputStream = Files.newInputStream(Paths.get(configFilePath))) {
      LOGGER.info("Start to read config file {}", configFilePath);
      Properties properties = new Properties();
      properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
      commonProperties.putAll(properties);
      return fromProperties(commonProperties);
    } catch (FileNotFoundException e) {
      LOGGER.error("Fail to find config file {}, reject DataNode startup.", configFilePath, e);
      System.exit(-1);
    } catch (IOException e) {
      LOGGER.error("Cannot load config file, reject DataNode startup.", e);
      System.exit(-1);
    } catch (Exception e) {
      LOGGER.error("Incorrect format in config file, reject DataNode startup.", e);
      System.exit(-1);
    }
    return null;
  }

  public static MqttConfig fromProperties(TrimProperties properties) {
    MqttConfig conf = new MqttConfig();
    conf.setMqttDir(properties.getProperty("mqtt_root_dir", conf.getMqttDir()));

    if (properties.getProperty(MqttConstant.MQTT_HOST_NAME) != null) {
      conf.setMqttHost(properties.getProperty(MqttConstant.MQTT_HOST_NAME));
    }

    if (properties.getProperty(MqttConstant.MQTT_PORT_NAME) != null) {
      conf.setMqttPort(
          Integer.parseInt(properties.getProperty(MqttConstant.MQTT_PORT_NAME)));
    }

    if (properties.getProperty(MqttConstant.MQTT_HANDLER_POOL_SIZE_NAME) != null) {
      conf.setMqttHandlerPoolSize(
          Integer.parseInt(
              properties.getProperty(MqttConstant.MQTT_HANDLER_POOL_SIZE_NAME)));
    }

    if (properties.getProperty(MqttConstant.MQTT_PAYLOAD_FORMATTER_NAME) != null) {
      conf.setMqttPayloadFormatter(
          properties.getProperty(MqttConstant.MQTT_PAYLOAD_FORMATTER_NAME));
    }

    if (properties.getProperty(MqttConstant.MQTT_DATA_PATH) != null) {
      conf.setMqttDataPath(properties.getProperty(MqttConstant.MQTT_DATA_PATH));
    }

    if (properties.getProperty(MqttConstant.MQTT_MAX_MESSAGE_SIZE) != null) {
      conf.setMqttMaxMessageSize(
          Integer.parseInt(properties.getProperty(MqttConstant.MQTT_MAX_MESSAGE_SIZE)));
    }

    if (properties.getProperty(MqttConstant.IOTDB_HOST_NAME) != null) {
      conf.setIotdbHost(
          properties.getProperty(MqttConstant.IOTDB_HOST_NAME));
    }

    if (properties.getProperty(MqttConstant.IOTDB_CLIENT_RPC_PORT_NAME) != null) {
      conf.setIotdbPort(
          Integer.parseInt(properties.getProperty(MqttConstant.IOTDB_CLIENT_RPC_PORT_NAME)));
    }

    conf.init();
    return conf;
  }
}
