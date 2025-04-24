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

import org.apache.iotdb.collector.plugin.builtin.sink.client.IoTDBDataNodeSyncClientManager;
import org.apache.iotdb.collector.plugin.builtin.sink.client.IoTDBSyncClient;
import org.apache.iotdb.collector.plugin.builtin.sink.client.IoTDBSyncClientManager;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeRawTabletInsertionEvent;
import org.apache.iotdb.collector.plugin.builtin.sink.event.PipeTsFileInsertionEvent;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.batch.PipeTabletEventBatch;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.batch.PipeTabletEventPlainBatch;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.batch.PipeTabletEventTsFileBatch;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.batch.PipeTransferBatchReqBuilder;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferFilePieceReq;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferTabletRawReqV2;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferTsFilePieceReq;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferTsFilePieceWithModReq;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.thrift.request.PipeTransferTsFileSealWithModReq;
import org.apache.iotdb.collector.utils.cacher.LeaderCacheUtils;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeConnectorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSinkRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;
import org.apache.iotdb.pipe.api.exception.PipeConnectionException;
import org.apache.iotdb.pipe.api.exception.PipeException;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferReq;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferResp;

import org.apache.commons.io.FileUtils;
import org.apache.tsfile.exception.write.WriteProcessException;
import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class IoTDBDataRegionSyncConnector extends IoTDBSslSyncConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBDataRegionSyncConnector.class);

  private PipeTransferBatchReqBuilder tabletBatchBuilder;

  protected IoTDBDataNodeSyncClientManager clientManager;

  @Override
  protected IoTDBSyncClientManager constructClient(
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
      final boolean shouldMarkAsPipeRequest) {
    clientManager =
        new IoTDBDataNodeSyncClientManager(
            nodeUrls,
            useSSL,
            Objects.nonNull(trustStorePath)
                ? /*IoTDBConfig.addDataHomeDir(trustStorePath)*/ ""
                : null,
            trustStorePwd,
            useLeaderCache,
            loadBalanceStrategy,
            username,
            password,
            shouldReceiverConvertOnTypeMismatch,
            loadTsFileStrategy,
            validateTsFile,
            shouldMarkAsPipeRequest);
    return clientManager;
  }

  @Override
  public void customize(
      final PipeParameters parameters, final PipeSinkRuntimeConfiguration configuration)
      throws Exception {
    super.customize(parameters, configuration);

    // tablet batch mode configuration
    if (isTabletBatchModeEnabled) {
      tabletBatchBuilder = new PipeTransferBatchReqBuilder(parameters);
    }
  }

  @Override
  protected PipeTransferFilePieceReq getTransferSingleFilePieceReq(
      final String fileName, final long position, final byte[] payLoad) throws IOException {
    return PipeTransferTsFilePieceReq.toTPipeTransferReq(fileName, position, payLoad);
  }

  @Override
  protected PipeTransferFilePieceReq getTransferMultiFilePieceReq(
      final String fileName, final long position, final byte[] payLoad) throws IOException {
    return PipeTransferTsFilePieceWithModReq.toTPipeTransferReq(fileName, position, payLoad);
  }

  @Override
  public void customize(
      PipeParameters pipeParameters, PipeConnectorRuntimeConfiguration pipeSinkRuntimeConfiguration)
      throws Exception {}

  @Override
  public void transfer(final TabletInsertionEvent tabletInsertionEvent) throws Exception {
    if (isTabletBatchModeEnabled) {
      final Pair<TEndPoint, PipeTabletEventBatch> endPointAndBatch =
          tabletBatchBuilder.onEvent(tabletInsertionEvent);
      if (Objects.nonNull(endPointAndBatch)) {
        doTransferWrapper(endPointAndBatch);
      }
    } else {
      doTransferWrapper((PipeRawTabletInsertionEvent) tabletInsertionEvent);
    }
  }

  @Override
  public void transfer(final TsFileInsertionEvent tsFileInsertionEvent) throws Exception {
    // In order to commit in order
    if (isTabletBatchModeEnabled && !tabletBatchBuilder.isEmpty()) {
      doTransferWrapper();
    }

    doTransferWrapper((PipeTsFileInsertionEvent) tsFileInsertionEvent);
  }

  @Override
  public void transfer(final Event event) throws Exception {
    if (isTabletBatchModeEnabled && !tabletBatchBuilder.isEmpty()) {
      doTransferWrapper();
    }
  }

  private void doTransferWrapper() throws IOException, WriteProcessException {
    for (final Pair<TEndPoint, PipeTabletEventBatch> nonEmptyBatch :
        tabletBatchBuilder.getAllNonEmptyBatches()) {
      doTransferWrapper(nonEmptyBatch);
    }
  }

  private void doTransferWrapper(final Pair<TEndPoint, PipeTabletEventBatch> endPointAndBatch)
      throws IOException, WriteProcessException {
    final PipeTabletEventBatch batch = endPointAndBatch.getRight();
    if (batch instanceof PipeTabletEventPlainBatch) {
      doTransfer(endPointAndBatch.getLeft(), (PipeTabletEventPlainBatch) batch);
    } else if (batch instanceof PipeTabletEventTsFileBatch) {
      doTransfer((PipeTabletEventTsFileBatch) batch);
    } else {
      LOGGER.warn("Unsupported batch type {}.", batch.getClass());
    }
    batch.onSuccess();
  }

  private void doTransfer(
      final TEndPoint endPoint, final PipeTabletEventPlainBatch batchToTransfer) {
    final Pair<IoTDBSyncClient, Boolean> clientAndStatus = clientManager.getClient(endPoint);

    final TPipeTransferResp resp;
    try {
      final TPipeTransferReq uncompressedReq = batchToTransfer.toTPipeTransferReq();
      final TPipeTransferReq req = compressIfNeeded(uncompressedReq);

      resp = clientAndStatus.getLeft().pipeTransfer(req);
    } catch (final Exception e) {
      clientAndStatus.setRight(false);
      throw new PipeConnectionException(
          String.format("Network error when transfer tablet batch, because %s.", e.getMessage()),
          e);
    }

    final TSStatus status = resp.getStatus();
    // Only handle the failed statuses to avoid string format performance overhead
    if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      receiverStatusHandler.handle(
          resp.getStatus(),
          String.format("Transfer PipeTransferTabletBatchReq error, result status %s", resp.status),
          batchToTransfer.deepCopyEvents().toString());
    }

    for (final Pair<String, TEndPoint> redirectPair :
        LeaderCacheUtils.parseRecommendedRedirections(status)) {
      clientManager.updateLeaderCache(redirectPair.getLeft(), redirectPair.getRight());
    }
  }

  private void doTransfer(final PipeTabletEventTsFileBatch batchToTransfer)
      throws IOException, WriteProcessException {
    final List<Pair<String, File>> dbTsFilePairs = batchToTransfer.sealTsFiles();
    final Map<Pair<String, Long>, Double> pipe2WeightMap = batchToTransfer.deepCopyPipe2WeightMap();

    for (final Pair<String, File> dbTsFile : dbTsFilePairs) {
      doTransfer(dbTsFile.right, null, dbTsFile.left);
      try {
        FileUtils.delete(dbTsFile.right);
      } catch (final NoSuchFileException e) {
        LOGGER.info("The file {} is not found, may already be deleted.", dbTsFile);
      } catch (final Exception e) {
        LOGGER.warn(
            "Failed to delete batch file {}, this file should be deleted manually later", dbTsFile);
      }
    }
  }

  private void doTransferWrapper(final PipeRawTabletInsertionEvent pipeRawTabletInsertionEvent)
      throws PipeException {
    doTransfer(pipeRawTabletInsertionEvent);
  }

  private void doTransfer(final PipeRawTabletInsertionEvent pipeRawTabletInsertionEvent)
      throws PipeException {
    final Pair<IoTDBSyncClient, Boolean> clientAndStatus =
        clientManager.getClient(pipeRawTabletInsertionEvent.getDeviceId());
    final TPipeTransferResp resp;

    try {
      final TPipeTransferReq req =
          compressIfNeeded(
              PipeTransferTabletRawReqV2.toTPipeTransferReq(
                  pipeRawTabletInsertionEvent.getTablet(),
                  pipeRawTabletInsertionEvent.isAligned(),
                  pipeRawTabletInsertionEvent.isTableModelEvent()
                      ? pipeRawTabletInsertionEvent.getTableModelDatabaseName()
                      : null));
      resp = clientAndStatus.getLeft().pipeTransfer(req);
    } catch (final Exception e) {
      clientAndStatus.setRight(false);
      throw new PipeConnectionException(
          String.format(
              "Network error when transfer raw tablet insertion event, because %s.",
              e.getMessage()),
          e);
    }

    final TSStatus status = resp.getStatus();
    // Only handle the failed statuses to avoid string format performance overhead
    if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      receiverStatusHandler.handle(
          status,
          String.format(
              "Transfer PipeRawTabletInsertionEvent %s error, result status %s",
              pipeRawTabletInsertionEvent, status),
          pipeRawTabletInsertionEvent.toString());
    }
    if (status.isSetRedirectNode()) {
      clientManager.updateLeaderCache(
          pipeRawTabletInsertionEvent.getDeviceId(), status.getRedirectNode());
    }
  }

  private void doTransferWrapper(final PipeTsFileInsertionEvent pipeTsFileInsertionEvent)
      throws PipeException, IOException {
    doTransfer(
        pipeTsFileInsertionEvent.getTsFile(),
        null,
        pipeTsFileInsertionEvent.isTableModelEvent()
            ? pipeTsFileInsertionEvent.getTableModelDatabaseName()
            : null);
  }

  private void doTransfer(final File tsFile, final File modFile, final String dataBaseName)
      throws PipeException, IOException {

    final Pair<IoTDBSyncClient, Boolean> clientAndStatus = clientManager.getClient();
    final TPipeTransferResp resp;

    // 1. Transfer tsFile, and mod file if exists and receiver's version >= 2
    if (Objects.nonNull(modFile) && clientManager.supportModsIfIsDataNodeReceiver()) {
      transferFilePieces(modFile, clientAndStatus, true);
      transferFilePieces(tsFile, clientAndStatus, true);

      // 2. Transfer file seal signal with mod, which means the file is transferred completely
      try {
        final TPipeTransferReq req =
            compressIfNeeded(
                PipeTransferTsFileSealWithModReq.toTPipeTransferReq(
                    modFile.getName(),
                    modFile.length(),
                    tsFile.getName(),
                    tsFile.length(),
                    dataBaseName));

        resp = clientAndStatus.getLeft().pipeTransfer(req);
      } catch (final Exception e) {
        clientAndStatus.setRight(false);
        clientManager.adjustTimeoutIfNecessary(e);
        throw new PipeConnectionException(
            String.format("Network error when seal file %s, because %s.", tsFile, e.getMessage()),
            e);
      }
    } else {
      transferFilePieces(tsFile, clientAndStatus, false);

      // 2. Transfer file seal signal without mod, which means the file is transferred completely
      try {
        final TPipeTransferReq req =
            compressIfNeeded(
                PipeTransferTsFileSealWithModReq.toTPipeTransferReq(
                    tsFile.getName(), tsFile.length(), dataBaseName));

        resp = clientAndStatus.getLeft().pipeTransfer(req);
      } catch (final Exception e) {
        clientAndStatus.setRight(false);
        clientManager.adjustTimeoutIfNecessary(e);
        throw new PipeConnectionException(
            String.format("Network error when seal file %s, because %s.", tsFile, e.getMessage()),
            e);
      }
    }

    final TSStatus status = resp.getStatus();
    // Only handle the failed statuses to avoid string format performance overhead
    if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      receiverStatusHandler.handle(
          resp.getStatus(),
          String.format("Seal file %s error, result status %s.", tsFile, resp.getStatus()),
          tsFile.getName());
    }

    LOGGER.info("Successfully transferred file {}.", tsFile);
  }

  @Override
  public void close() throws Exception {
    if (tabletBatchBuilder != null) {
      tabletBatchBuilder.close();
    }

    super.close();
  }
}
