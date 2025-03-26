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

package org.apache.iotdb.collector.runtime.plugin.utils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.apache.iotdb.collector.config.PluginRuntimeOptions.PLUGIN_INSTALL_LIB_DIR;
import static org.apache.iotdb.collector.config.PluginRuntimeOptions.PLUGIN_LIB_DIR;

public class PluginFileUtils {

  public static void savePluginToInstallDir(
      final String pluginName, final String jarName, final String jarNameWithMD5)
      throws IOException {
    final Path pluginInstallPath =
        Paths.get(PLUGIN_INSTALL_LIB_DIR.value() + File.separator + pluginName);
    final Path pluginJarInstallPath =
        Paths.get(getPluginJarFileWithMD5FilePath(pluginName, jarNameWithMD5));

    if (!Files.exists(pluginInstallPath)) {
      FileUtils.forceMkdir(pluginInstallPath.toFile());
    }
    if (Files.exists(pluginJarInstallPath)) {
      return;
    }

    FileUtils.moveFile(
        new File(getPluginJarFilePath(jarName)),
        pluginJarInstallPath.toFile(),
        StandardCopyOption.REPLACE_EXISTING);
  }

  public static boolean isPluginJarFileExist(final String jarName) {
    return Files.exists(Paths.get(getPluginJarFilePath(jarName)));
  }

  public static String getPluginJarFilePath(final String jarName) {
    return PLUGIN_LIB_DIR.value() + File.separator + jarName;
  }

  public static String getPluginJarFileWithMD5FilePath(
      final String pluginName, final String jarNameWithMD5) {
    return getPluginInstallDirPath(pluginName) + File.separator + jarNameWithMD5;
  }

  public static String getPluginInstallDirPath(final String pluginName) {
    return PLUGIN_INSTALL_LIB_DIR.value() + File.separator + pluginName;
  }

  public static String getPluginJarFileNameWithMD5(final String jarName, final String jarMD5) {
    return FilenameUtils.getBaseName(jarName)
        + "-"
        + jarMD5
        + "."
        + FilenameUtils.getExtension(jarName);
  }

  public static void removePluginFileUnderLibRoot(final String pluginName, final String fileName)
      throws IOException {
    final Path pluginDirPath = Paths.get(getPluginInstallFilePath(pluginName, fileName));

    Files.deleteIfExists(pluginDirPath);
    Files.deleteIfExists(pluginDirPath.getParent());
  }

  public static String getPluginInstallFilePath(final String pluginName, final String fileName) {
    return PLUGIN_INSTALL_LIB_DIR.value() + File.separator + pluginName + File.separator + fileName;
  }
}
