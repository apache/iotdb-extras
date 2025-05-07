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

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.collector.runtime.task.TaskStateEnum;
import org.apache.iotdb.collector.runtime.task.event.ProgressReportEvent;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.collector.utils.SerializationUtil;

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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class TaskPersistence extends Persistence {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskPersistence.class);

  public TaskPersistence(String databaseUrl) {
    super(databaseUrl);
  }

  @Override
  protected void initDatabaseFileIfPossible() {
    try {
      final Path taskDatabaseFilePath =
          Paths.get(TaskRuntimeOptions.TASK_DATABASE_FILE_PATH.value());
      if (!Files.exists(taskDatabaseFilePath)) {
        Files.createFile(taskDatabaseFilePath);
      }
    } catch (final IOException e) {
      LOGGER.warn("Failed to create task database file", e);
    }
  }

  @Override
  protected void initTableIfPossible() {
    try (final Connection connection = getConnection();
        final PreparedStatement statement =
            connection.prepareStatement(DBConstant.CREATE_TASK_TABLE_SQL)) {
      statement.executeUpdate();
    } catch (final SQLException e) {
      LOGGER.warn("Failed to create task database", e);
    }
  }

  @Override
  public void tryResume() {
    final String queryAllTaskSQL =
        "SELECT task_id, task_state, source_attribute, processor_attribute, sink_attribute, create_time FROM task";

    try (final Connection connection = getConnection();
        final PreparedStatement statement = connection.prepareStatement(queryAllTaskSQL);
        final ResultSet taskResultSet = statement.executeQuery()) {
      while (taskResultSet.next()) {
        final String taskId = taskResultSet.getString(1);
        final TaskStateEnum taskState = TaskStateEnum.values()[taskResultSet.getInt(2)];
        final byte[] sourceAttribute = taskResultSet.getBytes(3);
        final byte[] processorAttribute = taskResultSet.getBytes(4);
        final byte[] sinkAttribute = taskResultSet.getBytes(5);

        tryRecoverTask(
            taskId,
            taskState,
            SerializationUtil.deserialize(sourceAttribute),
            SerializationUtil.deserialize(processorAttribute),
            SerializationUtil.deserialize(sinkAttribute));
      }
    } catch (final SQLException e) {
      LOGGER.warn("Failed to resume task persistence message, because {}", e.getMessage());
    }
  }

  public void tryRecoverTask(
      final String taskId,
      final TaskStateEnum taskState,
      final Map<String, String> sourceAttribute,
      final Map<String, String> processorAttribute,
      final Map<String, String> sinkAttribute) {
    final Response response =
        RuntimeService.task().isPresent()
            ? RuntimeService.task()
                .get()
                .createTask(
                    taskId, taskState, sourceAttribute, processorAttribute, sinkAttribute, false)
            : null;

    if (Objects.isNull(response) || response.getStatus() != Response.Status.OK.getStatusCode()) {
      LOGGER.warn("Failed to recover task persistence message, because {}", response);
      tryDeleteTask(taskId);
    }
  }

  public void tryPersistenceTask(
      final String taskId,
      final TaskStateEnum taskState,
      final Map<String, String> sourceAttribute,
      final Map<String, String> processorAttribute,
      final Map<String, String> sinkAttribute) {
    final String insertSQL =
        "INSERT INTO task(task_id, task_state , source_attribute, processor_attribute, sink_attribute,task_progress, create_time) values(?, ?,?, ?, ?, ?, ?)";

    try (final Connection connection = getConnection();
        final PreparedStatement statement = connection.prepareStatement(insertSQL)) {

      final byte[] sourceAttributeBuffer = SerializationUtil.serialize(sourceAttribute);
      final byte[] processorAttributeBuffer = SerializationUtil.serialize(processorAttribute);
      final byte[] sinkAttributeBuffer = SerializationUtil.serialize(sinkAttribute);

      statement.setString(1, taskId);
      statement.setInt(2, taskState.getTaskState());
      statement.setBytes(3, sourceAttributeBuffer);
      statement.setBytes(4, processorAttributeBuffer);
      statement.setBytes(5, sinkAttributeBuffer);
      statement.setBytes(6, null);
      statement.setString(7, String.valueOf(new Timestamp(System.currentTimeMillis())));
      statement.executeUpdate();

      LOGGER.info("successfully persisted task {} info", taskId);
    } catch (final SQLException | IOException e) {
      LOGGER.warn("Failed to persistence task message, because {}", e.getMessage());
    }
  }

  public void tryDeleteTask(final String taskId) {
    final String deleteSQL = "DELETE FROM task WHERE task_id = ?";

    try (final Connection connection = getConnection();
        final PreparedStatement statement = connection.prepareStatement(deleteSQL)) {
      statement.setString(1, taskId);
      statement.executeUpdate();

      LOGGER.info("successfully deleted task {}", taskId);
    } catch (final SQLException e) {
      LOGGER.warn("Failed to delete task persistence message, because {}", e.getMessage());
    }
  }

  public void tryAlterTaskState(final String taskId, final TaskStateEnum taskState) {
    final String alterSQL = "UPDATE task SET task_state = ? WHERE task_id = ?";

    try (final Connection connection = getConnection();
        final PreparedStatement statement = connection.prepareStatement(alterSQL)) {
      statement.setInt(1, taskState.getTaskState());
      statement.setString(2, taskId);
      statement.executeUpdate();

      LOGGER.info("successfully altered task {}", taskId);
    } catch (final SQLException e) {
      LOGGER.warn("Failed to alter task persistence message, because {}", e.getMessage());
    }
  }

  public void tryReportTaskProgress(final ProgressReportEvent reportEvent) {
    if (reportEvent.getInstancesProgress() == null
        || reportEvent.getInstancesProgress().isEmpty()) {
      return;
    }

    final String reportSQL = "UPDATE task SET task_progress = ? WHERE task_id = ?";

    try (final Connection connection = getConnection();
        final PreparedStatement statement = connection.prepareStatement(reportSQL)) {
      statement.setBytes(
          1, SerializationUtil.serializeInstances(reportEvent.getInstancesProgress()));
      statement.setString(2, reportEvent.getTaskId());
      statement.executeUpdate();
    } catch (final SQLException | IOException e) {
      LOGGER.warn("Failed to report task progress because {}", e.getMessage());
    }
  }

  public Optional<Map<Integer, ProgressIndex>> getTasksProgress(final String taskId) {
    final String queryProgressSQL = "SELECT task_progress FROM task WHERE task_id = ?";

    try (final Connection connection = getConnection();
        final PreparedStatement statement = connection.prepareStatement(queryProgressSQL)) {
      statement.setString(1, taskId);

      try (final ResultSet resultSet = statement.executeQuery()) {
        final byte[] bytes = resultSet.getBytes("task_progress");

        return bytes == null
            ? Optional.empty()
            : Optional.of(SerializationUtil.deserializeInstances(bytes));
      }
    } catch (final SQLException e) {
      LOGGER.warn("Failed to retrieve task progress because {}", e.getMessage());
    }

    return Optional.empty();
  }
}
