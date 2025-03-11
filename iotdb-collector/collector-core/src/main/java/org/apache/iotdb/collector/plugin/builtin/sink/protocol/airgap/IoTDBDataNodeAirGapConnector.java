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

package org.apache.iotdb.collector.plugin.builtin.sink.protocol.airgap;

import org.apache.iotdb.collector.config.PipeOptions;
import org.apache.iotdb.collector.plugin.builtin.annotation.TableModel;
import org.apache.iotdb.collector.plugin.builtin.annotation.TreeModel;
import org.apache.iotdb.collector.plugin.builtin.sink.constant.PipeTransferHandshakeConstant;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.request.PipeTransferDataNodeHandshakeV1Req;
import org.apache.iotdb.collector.plugin.builtin.sink.payload.evolvable.request.PipeTransferDataNodeHandshakeV2Req;
import org.apache.iotdb.collector.plugin.builtin.sink.utils.NodeUrlUtils;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

@TreeModel
@TableModel
public abstract class IoTDBDataNodeAirGapConnector extends IoTDBAirGapConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBDataNodeAirGapConnector.class);

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);

    final Set<TEndPoint> givenNodeUrls = parseNodeUrls(validator.getParameters());

    validator.validate(
        empty -> {
          try {
            // Ensure the sink doesn't point to the air gap receiver on DataNode itself
            return !(PipeOptions.PIPE_AIR_GAP_RECEIVER_ENABLED.value()
                && NodeUrlUtils.containsLocalAddress(
                    givenNodeUrls.stream()
                        .filter(
                            tEndPoint ->
                                tEndPoint.getPort()
                                    == PipeOptions.PIPE_AIR_GAP_RECEIVER_PORT.value())
                        .map(TEndPoint::getIp)
                        .collect(Collectors.toList())));
          } catch (final UnknownHostException e) {
            LOGGER.warn("Unknown host when checking pipe sink IP.", e);
            return false;
          }
        },
        String.format(
            "One of the endpoints %s of the receivers is pointing back to the air gap receiver %s on sender itself, or unknown host when checking pipe sink IP.",
            givenNodeUrls,
            new TEndPoint(
                PipeOptions.RPC_ADDRESS.value(), PipeOptions.PIPE_AIR_GAP_RECEIVER_PORT.value())));
  }

  @Override
  protected boolean mayNeedHandshakeWhenFail() {
    return false;
  }

  @Override
  protected byte[] generateHandShakeV1Payload() throws IOException {
    return PipeTransferDataNodeHandshakeV1Req.toTPipeTransferBytes(
        PipeOptions.TIMESTAMP_PRECISION.value());
  }

  @Override
  protected byte[] generateHandShakeV2Payload() throws IOException {
    final HashMap<String, String> params = new HashMap<>();
    params.put(PipeTransferHandshakeConstant.HANDSHAKE_KEY_CLUSTER_ID, "");
    params.put(
        PipeTransferHandshakeConstant.HANDSHAKE_KEY_TIME_PRECISION,
        PipeOptions.TIMESTAMP_PRECISION.value());
    params.put(
        PipeTransferHandshakeConstant.HANDSHAKE_KEY_CONVERT_ON_TYPE_MISMATCH,
        Boolean.toString(shouldReceiverConvertOnTypeMismatch));
    params.put(
        PipeTransferHandshakeConstant.HANDSHAKE_KEY_LOAD_TSFILE_STRATEGY, loadTsFileStrategy);
    params.put(PipeTransferHandshakeConstant.HANDSHAKE_KEY_USERNAME, username);
    params.put(PipeTransferHandshakeConstant.HANDSHAKE_KEY_PASSWORD, password);
    params.put(
        PipeTransferHandshakeConstant.HANDSHAKE_KEY_VALIDATE_TSFILE,
        Boolean.toString(loadTsFileValidation));
    params.put(
        PipeTransferHandshakeConstant.HANDSHAKE_KEY_MARK_AS_PIPE_REQUEST,
        Boolean.toString(shouldMarkAsPipeRequest));

    return PipeTransferDataNodeHandshakeV2Req.toTPipeTransferBytes(params);
  }
}
