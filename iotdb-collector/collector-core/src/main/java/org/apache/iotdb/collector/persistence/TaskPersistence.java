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
import org.apache.iotdb.collector.runtime.task.TaskStateEnum;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.tsfile.utils.PublicBAOS;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    try (final Connection connection = getConnection()) {
      final PreparedStatement statement =
          connection.prepareStatement(DBConstant.CREATE_TASK_TABLE_SQL);
      statement.executeUpdate();
    } catch (final SQLException e) {
      LOGGER.warn("Failed to create task database", e);
    }
  }

  @Override
  public void tryResume() {
    final String queryAllTaskSQL =
        "SELECT task_id, task_state, source_attribute, processor_attribute, sink_attribute, create_time FROM task";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(queryAllTaskSQL);
      final ResultSet taskResultSet = statement.executeQuery();

      while (taskResultSet.next()) {
        final String taskId = taskResultSet.getString(1);
        final TaskStateEnum taskState = TaskStateEnum.values()[taskResultSet.getInt(2)];
        final byte[] sourceAttribute = taskResultSet.getBytes(3);
        final byte[] processorAttribute = taskResultSet.getBytes(4);
        final byte[] sinkAttribute = taskResultSet.getBytes(5);

        tryRecoverTask(
            taskId,
            taskState,
            deserialize(sourceAttribute),
            deserialize(processorAttribute),
            deserialize(sinkAttribute));
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

  private Map<String, String> deserialize(final byte[] buffer) {
    final Map<String, String> attribute = new HashMap<>();
    final ByteBuffer attributeBuffer = ByteBuffer.wrap(buffer);

    final int size = ReadWriteIOUtils.readInt(attributeBuffer);
    for (int i = 0; i < size; i++) {
      final String key = ReadWriteIOUtils.readString(attributeBuffer);
      final String value = ReadWriteIOUtils.readString(attributeBuffer);

      attribute.put(key, value);
    }

    return attribute;
  }

  public void tryPersistenceTask(
      final String taskId,
      final TaskStateEnum taskState,
      final Map<String, String> sourceAttribute,
      final Map<String, String> processorAttribute,
      final Map<String, String> sinkAttribute) {
    final String insertSQL =
        "INSERT INTO task(task_id, task_state , source_attribute, processor_attribute, sink_attribute, create_time) values(?, ?, ?, ?, ?, ?)";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(insertSQL);

      final byte[] sourceAttributeBuffer = serialize(sourceAttribute);
      final byte[] processorAttributeBuffer = serialize(processorAttribute);
      final byte[] sinkAttributeBuffer = serialize(sinkAttribute);

      statement.setString(1, taskId);
      statement.setInt(2, taskState.getTaskState());
      statement.setBytes(3, sourceAttributeBuffer);
      statement.setBytes(4, processorAttributeBuffer);
      statement.setBytes(5, sinkAttributeBuffer);
      statement.setString(6, String.valueOf(new Timestamp(System.currentTimeMillis())));
      statement.executeUpdate();

      LOGGER.info("successfully persisted task {} info", taskId);
    } catch (final SQLException | IOException e) {
      LOGGER.warn("Failed to persistence task message, because {}", e.getMessage());
    }
  }

  private byte[] serialize(final Map<String, String> attribute) throws IOException {
    try (final PublicBAOS byteArrayOutputStream = new PublicBAOS();
        final DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
      ReadWriteIOUtils.write(attribute.size(), outputStream);
      for (final Map.Entry<String, String> entry : attribute.entrySet()) {
        ReadWriteIOUtils.write(entry.getKey(), outputStream);
        ReadWriteIOUtils.write(entry.getValue(), outputStream);
      }

      return ByteBuffer.wrap(byteArrayOutputStream.getBuf(), 0, byteArrayOutputStream.size())
          .array();
    }
  }

  public void tryDeleteTask(final String taskId) {
    final String deleteSQL = "DELETE FROM task WHERE task_id = ?";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(deleteSQL);
      statement.setString(1, taskId);
      statement.executeUpdate();

      LOGGER.info("successfully deleted task {}", taskId);
    } catch (final SQLException e) {
      LOGGER.warn("Failed to delete task persistence message, because {}", e.getMessage());
    }
  }

  public void tryAlterTaskState(final String taskId, final TaskStateEnum taskState) {
    final String alterSQL = "UPDATE task SET task_state = ? WHERE task_id = ?";

    try (final Connection connection = getConnection()) {
      final PreparedStatement statement = connection.prepareStatement(alterSQL);
      statement.setInt(1, taskState.getTaskState());
      statement.setString(2, taskId);
      statement.executeUpdate();

      LOGGER.info("successfully altered task {}", taskId);
    } catch (SQLException e) {
      LOGGER.warn("Failed to alter task persistence message, because {}", e.getMessage());
    }
  }
}
