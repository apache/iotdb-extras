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

package org.apache.iotdb.collector.plugin.builtin.sink.protocol;

import org.apache.iotdb.collector.config.PipeRuntimeOptions;
import org.apache.iotdb.collector.plugin.builtin.sink.client.IoTDBSyncClient;
import org.apache.iotdb.collector.plugin.builtin.sink.client.IoTDBSyncClientManager;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferFilePieceReq;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.response.PipeTransferFilePieceResp;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSinkRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.exception.PipeConnectionException;
import org.apache.iotdb.pipe.api.exception.PipeException;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferReq;

import com.google.common.collect.ImmutableList;
import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;

import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_LEADER_CACHE_ENABLE_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.CONNECTOR_LEADER_CACHE_ENABLE_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_IOTDB_SSL_ENABLE_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_IOTDB_SSL_TRUST_STORE_PATH_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_IOTDB_SSL_TRUST_STORE_PWD_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_KEY;
import static org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeConnectorConstant.SINK_LEADER_CACHE_ENABLE_KEY;

public abstract class IoTDBSslSyncConnector extends IoTDBConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBSslSyncConnector.class);

  protected IoTDBSyncClientManager clientManager;

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);

    final PipeParameters parameters = validator.getParameters();

    final String userSpecifiedConnectorName =
        parameters
            .getStringOrDefault(
                ImmutableList.of(CONNECTOR_KEY, SINK_KEY), ""
                /*IOTDB_THRIFT_CONNECTOR.getPipePluginName()*/ )
            .toLowerCase();

    validator.validate(
        args -> !((boolean) args[0]) || ((boolean) args[1] && (boolean) args[2]),
        String.format(
            "When ssl transport is enabled, %s and %s must be specified",
            SINK_IOTDB_SSL_TRUST_STORE_PATH_KEY, SINK_IOTDB_SSL_TRUST_STORE_PWD_KEY),
        parameters.getBooleanOrDefault(SINK_IOTDB_SSL_ENABLE_KEY, false),
        parameters.hasAttribute(SINK_IOTDB_SSL_TRUST_STORE_PATH_KEY),
        parameters.hasAttribute(SINK_IOTDB_SSL_TRUST_STORE_PWD_KEY));
  }

  @Override
  public void customize(
      final PipeParameters parameters, final PipeSinkRuntimeConfiguration configuration)
      throws Exception {
    super.customize(parameters, configuration);

    final String trustStorePath = parameters.getString(SINK_IOTDB_SSL_TRUST_STORE_PATH_KEY);
    final String trustStorePwd = parameters.getString(SINK_IOTDB_SSL_TRUST_STORE_PWD_KEY);

    // leader cache configuration
    final boolean useLeaderCache =
        parameters.getBooleanOrDefault(
            Arrays.asList(SINK_LEADER_CACHE_ENABLE_KEY, CONNECTOR_LEADER_CACHE_ENABLE_KEY),
            CONNECTOR_LEADER_CACHE_ENABLE_DEFAULT_VALUE);

    clientManager =
        constructClient(
            nodeUrls,
            false,
            trustStorePath,
            trustStorePwd,
            useLeaderCache,
            loadBalanceStrategy,
            username,
            password,
            shouldReceiverConvertOnTypeMismatch,
            loadTsFileStrategy,
            loadTsFileValidation,
            shouldMarkAsPipeRequest);
  }

  protected abstract IoTDBSyncClientManager constructClient(
      final List<TEndPoint> nodeUrls,
      final boolean useSSL,
      final String trustStorePath,
      final String trustStorePwd,
      /* The following parameters are used locally. */
      final boolean useLeaderCache,
      final String loadBalanceStrategy,
      /* The following parameters are used to handshake with the receiver. */
      final String username,
      final String password,
      final boolean shouldReceiverConvertOnTypeMismatch,
      final String loadTsFileStrategy,
      final boolean validateTsFile,
      final boolean shouldMarkAsPipeRequest);

  @Override
  public void handshake() throws Exception {
    clientManager.checkClientStatusAndTryReconstructIfNecessary();
  }

  @Override
  public void heartbeat() {
    try {
      handshake();
    } catch (final Exception e) {
      LOGGER.warn(
          "Failed to reconnect to target server, because: {}. Try to reconnect later.",
          e.getMessage(),
          e);
    }
  }

  protected void transferFilePieces(
      final File file,
      final Pair<IoTDBSyncClient, Boolean> clientAndStatus,
      final boolean isMultiFile)
      throws PipeException, IOException {
    final int readFileBufferSize = PipeRuntimeOptions.PIPE_CONNECTOR_READ_FILE_BUFFER_SIZE.value();
    final byte[] readBuffer = new byte[readFileBufferSize];
    long position = 0;
    try (final RandomAccessFile reader = new RandomAccessFile(file, "r")) {
      while (true) {
        final int readLength = reader.read(readBuffer);
        if (readLength == -1) {
          break;
        }

        final byte[] payLoad =
            readLength == readFileBufferSize
                ? readBuffer
                : Arrays.copyOfRange(readBuffer, 0, readLength);
        final PipeTransferFilePieceResp resp;
        try {
          final TPipeTransferReq req =
              compressIfNeeded(
                  isMultiFile
                      ? getTransferMultiFilePieceReq(file.getName(), position, payLoad)
                      : getTransferSingleFilePieceReq(file.getName(), position, payLoad));

          resp =
              PipeTransferFilePieceResp.fromTPipeTransferResp(
                  clientAndStatus.getLeft().pipeTransfer(req));
        } catch (final Exception e) {
          clientAndStatus.setRight(false);
          throw new PipeConnectionException(
              String.format(
                  "Network error when transfer file %s, because %s.", file, e.getMessage()),
              e);
        }

        position += readLength;

        final TSStatus status = resp.getStatus();
        // This case only happens when the connection is broken, and the connector is reconnected
        // to the receiver, then the receiver will redirect the file position to the last position
        if (status.getCode() == TSStatusCode.PIPE_TRANSFER_FILE_OFFSET_RESET.getStatusCode()) {
          position = resp.getEndWritingOffset();
          reader.seek(position);
          LOGGER.info("Redirect file position to {}.", position);
          continue;
        }

        // Send handshake req and then re-transfer the event
        if (status.getCode()
            == TSStatusCode.PIPE_CONFIG_RECEIVER_HANDSHAKE_NEEDED.getStatusCode()) {
          clientManager.sendHandshakeReq(clientAndStatus);
        }
        // Only handle the failed statuses to avoid string format performance overhead
        if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
            && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
          receiverStatusHandler.handle(
              resp.getStatus(),
              String.format("Transfer file %s error, result status %s.", file, resp.getStatus()),
              file.getName());
        }
      }
    }
  }

  protected abstract PipeTransferFilePieceReq getTransferSingleFilePieceReq(
      final String fileName, final long position, final byte[] payLoad) throws IOException;

  protected abstract PipeTransferFilePieceReq getTransferMultiFilePieceReq(
      final String fileName, final long position, final byte[] payLoad) throws IOException;

  @Override
  public void close() throws Exception {
    if (clientManager != null) {
      clientManager.close();
    }
  }
}
