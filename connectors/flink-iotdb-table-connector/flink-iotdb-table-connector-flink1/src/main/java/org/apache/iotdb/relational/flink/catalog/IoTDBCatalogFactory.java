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

package org.apache.iotdb.relational.flink.catalog;

import org.apache.iotdb.relational.flink.cfg.IoTDBRelationalOptions;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.FactoryUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Flink CatalogFactory entry point for the IoTDB relational table model. */
public class IoTDBCatalogFactory implements CatalogFactory {

  @Override
  public String factoryIdentifier() {
    return IoTDBRelationalOptions.IDENTIFIER;
  }

  @Override
  public Set<ConfigOption<?>> requiredOptions() {
    return new HashSet<>(Arrays.asList(IoTDBRelationalOptions.NODE_URLS));
  }

  @Override
  public Set<ConfigOption<?>> optionalOptions() {
    return new HashSet<>(
        Arrays.asList(
            IoTDBRelationalOptions.USER,
            IoTDBRelationalOptions.PASSWORD,
            IoTDBRelationalOptions.DEFAULT_DATABASE));
  }

  @Override
  public Catalog createCatalog(Context context) {
    FactoryUtil.CatalogFactoryHelper helper = FactoryUtil.createCatalogFactoryHelper(this, context);
    helper.validate();
    IoTDBRelationalOptions options = toOptions(helper.getOptions());
    return new IoTDBCatalog(context.getName(), options.getDefaultDatabase(), options);
  }

  private static IoTDBRelationalOptions toOptions(ReadableConfig config) {
    return IoTDBRelationalOptions.builder()
        .withNodeUrls(
            Arrays.asList(((String) config.get(IoTDBRelationalOptions.NODE_URLS)).split(",")))
        .withUsername(config.get(IoTDBRelationalOptions.USER))
        .withPassword(config.get(IoTDBRelationalOptions.PASSWORD))
        .withDefaultDatabase(config.get(IoTDBRelationalOptions.DEFAULT_DATABASE))
        .build();
  }
}
