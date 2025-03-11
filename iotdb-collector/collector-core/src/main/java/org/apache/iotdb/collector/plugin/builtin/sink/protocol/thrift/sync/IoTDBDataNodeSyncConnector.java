/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.collector.plugin.builtin.sink.protocol.thrift.sync;

import org.apache.iotdb.collector.config.PipeOptions;
import org.apache.iotdb.collector.plugin.builtin.annotation.TableModel;
import org.apache.iotdb.collector.plugin.builtin.annotation.TreeModel;
import org.apache.iotdb.collector.plugin.builtin.sink.protocol.thrift.client.IoTDBDataNodeSyncClientManager;
import org.apache.iotdb.collector.plugin.builtin.sink.protocol.thrift.client.IoTDBSyncClientManager;
import org.apache.iotdb.collector.plugin.builtin.sink.utils.NodeUrlUtils;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@TreeModel
@TableModel
public abstract class IoTDBDataNodeSyncConnector extends IoTDBSslSyncConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBDataNodeSyncConnector.class);

  protected IoTDBDataNodeSyncClientManager clientManager;

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);

    final Set<TEndPoint> givenNodeUrls = parseNodeUrls(validator.getParameters());

    validator.validate(
        empty -> {
          try {
            // Ensure the sink doesn't point to the thrift receiver on DataNode itself
            return !NodeUrlUtils.containsLocalAddress(
                givenNodeUrls.stream()
                    .filter(tEndPoint -> tEndPoint.getPort() == PipeOptions.RPC_PORT.value())
                    .map(TEndPoint::getIp)
                    .collect(Collectors.toList()));
          } catch (final UnknownHostException e) {
            LOGGER.warn("Unknown host when checking pipe sink IP.", e);
            return false;
          }
        },
        String.format(
            "One of the endpoints %s of the receivers is pointing back to the thrift receiver %s on sender itself, "
                + "or unknown host when checking pipe sink IP.",
            givenNodeUrls,
            new TEndPoint(PipeOptions.RPC_ADDRESS.value(), PipeOptions.RPC_PORT.value())));
  }

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
            Objects.nonNull(trustStorePath) ? /*IoTDBConfig.addDataHomeDir(trustStorePath)*/ "" : null,
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
}
