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

package org.apache.iotdb.collector.persistence;

import org.apache.iotdb.collector.runtime.plugin.utils.PluginFileUtils;
import org.apache.iotdb.collector.service.RuntimeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

public class PluginPersistence extends Persistence {

  private static final Logger LOGGER = LoggerFactory.getLogger(PluginPersistence.class);

  public PluginPersistence(String databaseUrl) {
    super(databaseUrl);
  }

  @Override
  protected void initDatabaseFileIfPossible() {
    try {
      final Path pluginDatabaseFilePath = Paths.get(DBConstant.PLUGIN_DATABASE_FILE_PATH);
      if (!Files.exists(pluginDatabaseFilePath)) {
        Files.createFile(pluginDatabaseFilePath);
      }
    } catch (final IOException e) {
      LOGGER.warn("Failed to create plugin database file", e);
    }
  }

  @Override
  protected void initTableIfPossible() {
    try (final Connection connection = getConnection()) {
      final PreparedStatement statement =
          connection.prepareStatement(DBConstant.CREATE_PLUGIN_TABLE_SQL);
      statement.executeUpdate();
    } catch (final SQLException e) {
      LOGGER.warn("Failed to create plugin database", e);
    }
  }

  @Override
  public void tryResume() {
    final String queryAllPluginSQL =
        "SELECT plugin_name, class_name, jar_name, jar_md5 FROM plugin";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(queryAllPluginSQL);
      final ResultSet pluginResultSet = statement.executeQuery();

      while (pluginResultSet.next()) {
        final String pluginName = pluginResultSet.getString("plugin_name");
        final String className = pluginResultSet.getString("class_name");
        final String jarName = pluginResultSet.getString("jar_name");
        final String jarMd5 = pluginResultSet.getString("jar_md5");

        if (!isPluginJarFileWithMD5NameExists(pluginName, jarName, jarMd5)) {
          tryDeletePlugin(pluginName);
          continue;
        }

        tryRecoverPlugin(pluginName, className, jarName, jarMd5);
      }
    } catch (final SQLException e) {
      LOGGER.warn("Failed to resume plugin persistence message, because {}", e.getMessage());
    }
  }

  private boolean isPluginJarFileWithMD5NameExists(
      final String pluginName, final String jarName, final String jarMd5) {
    final Path pluginJarFileWithMD5Path =
        Paths.get(
            PluginFileUtils.getPluginJarFileWithMD5FilePath(
                pluginName, PluginFileUtils.getPluginJarFileNameWithMD5(jarName, jarMd5)));

    return Files.exists(pluginJarFileWithMD5Path);
  }

  private void tryRecoverPlugin(
      final String pluginName, final String className, final String jarName, final String jarMD5) {
    final Response response =
        RuntimeService.plugin().isPresent()
            ? RuntimeService.plugin()
                .get()
                .createPlugin(pluginName, className, jarName, jarMD5, false)
            : null;

    if (Objects.isNull(response) || response.getStatus() != Response.Status.OK.getStatusCode()) {
      LOGGER.warn("Failed to recover plugin message from plugin {}: {}", pluginName, response);
    }
  }

  public void tryPersistencePlugin(
      final String pluginName, final String className, final String jarName, final String jarMD5) {
    final String sql =
        "INSERT INTO plugin(plugin_name, class_name, jar_name, jar_md5, create_time) VALUES(?,?,?,?,?)";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(sql);
      statement.setString(1, pluginName);
      statement.setString(2, className);
      statement.setString(3, jarName);
      statement.setString(4, jarMD5);
      statement.setString(5, String.valueOf(new Timestamp(System.currentTimeMillis())));
      statement.executeUpdate();
    } catch (final SQLException e) {
      LOGGER.warn("Failed to persistence plugin message, because {}", e.getMessage());
    }
  }

  public void tryDeletePlugin(final String pluginName) {
    final String sql = "DELETE FROM plugin WHERE plugin_name = ?";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(sql);
      statement.setString(1, pluginName);
      statement.executeUpdate();
    } catch (final SQLException e) {
      LOGGER.warn("Failed to delete plugin persistence message, because {}", e.getMessage());
    }
  }
}
