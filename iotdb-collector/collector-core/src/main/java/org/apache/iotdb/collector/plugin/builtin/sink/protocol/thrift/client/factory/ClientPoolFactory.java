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

package org.apache.iotdb.collector.plugin.builtin.sink.protocol.thrift.client.factory;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.iotdb.collector.config.PipeOptions;
import org.apache.iotdb.collector.plugin.builtin.sink.property.ClientPoolProperty;
import org.apache.iotdb.collector.plugin.builtin.sink.property.ThriftClientProperty;
import org.apache.iotdb.collector.plugin.builtin.sink.protocol.thrift.client.ClientManager;
import org.apache.iotdb.collector.plugin.builtin.sink.protocol.thrift.client.async.AsyncPipeDataTransferServiceClient;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
// import org.apache.iotdb.commons.concurrent.ThreadName;

public class ClientPoolFactory {

  private ClientPoolFactory() {}

  public static class AsyncPipeDataTransferServiceClientPoolFactory
      implements IClientPoolFactory<TEndPoint, AsyncPipeDataTransferServiceClient> {

    @Override
    public GenericKeyedObjectPool<TEndPoint, AsyncPipeDataTransferServiceClient> createClientPool(
        ClientManager<TEndPoint, AsyncPipeDataTransferServiceClient> manager) {
      final GenericKeyedObjectPool<TEndPoint, AsyncPipeDataTransferServiceClient> clientPool =
          new GenericKeyedObjectPool<>(
              new AsyncPipeDataTransferServiceClient.Factory(
                  manager,
                  new ThriftClientProperty.Builder()
                      .setConnectionTimeoutMs(
                          PipeOptions.PIPE_CONNECTOR_TRANSFER_TIMEOUT_MS.value())
                      .setRpcThriftCompressionEnabled(
                          PipeOptions.PIPE_CONNECTOR_RPC_THRIFT_COMPRESSION_ENABLED.value())
                      .setSelectorNumOfAsyncClientManager(
                          PipeOptions.PIPE_ASYNC_CONNECTOR_SELECTOR_NUMBER.value())
                      .build(),
                  ""
                  // ThreadName.PIPE_ASYNC_CONNECTOR_CLIENT_POOL.getName()
                  ),
              new ClientPoolProperty.Builder<AsyncPipeDataTransferServiceClient>()
                  .setMaxClientNumForEachNode(
                      PipeOptions.PIPE_ASYNC_CONNECTOR_MAX_CLIENT_NUMBER.value())
                  .build()
                  .getConfig());
      return clientPool;
    }
  }
}
