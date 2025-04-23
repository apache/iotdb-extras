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

package org.apache.iotdb.collector.config;

import java.io.File;

public class PluginRuntimeOptions extends Options {
  public static final Option<String> PLUGIN_LIB_DIR =
      new Option<String>("plugin_lib_dir", "system" + File.separator + "plugin") {
        @Override
        public void setValue(final String valueString) {
          value = addHomeDir(valueString);
        }
      };

  public static final Option<String> PLUGIN_INSTALL_LIB_DIR =
      new Option<String>(
          "plugin_install_lib_dir", PLUGIN_LIB_DIR.value() + File.separator + "install") {
        @Override
        public void setValue(final String valueString) {
          value = addHomeDir(valueString);
        }
      };

  public static final Option<String> PLUGIN_DATABASE_FILE_PATH =
      new Option<String>(
          "plugin_database_file_path",
          "system" + File.separator + "database" + File.separator + "plugin.db") {
        @Override
        public void setValue(final String valueString) {
          value = addHomeDir(valueString);
        }
      };
}
