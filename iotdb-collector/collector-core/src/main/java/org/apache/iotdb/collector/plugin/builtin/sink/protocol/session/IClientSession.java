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

package org.apache.iotdb.collector.plugin.builtin.sink.protocol.session;

import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class IClientSession {

  private long id;

  private String username;

  /** ip:port for thrift-based service and client id for mqtt-based service. */
  abstract String getConnectionId();

  public String getUsername() {
    return this.username;
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String toString() {
    return String.format("%d-%s:%s", getId(), getUsername(), getConnectionId());
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public enum SqlDialect {
    TREE((byte) 0),
    TABLE((byte) 1);

    private final byte dialect;

    SqlDialect(byte dialect) {
      this.dialect = dialect;
    }

    public void serialize(final DataOutputStream stream) throws IOException {
      ReadWriteIOUtils.write(dialect, stream);
    }

    public void serialize(final ByteBuffer buffer) {
      ReadWriteIOUtils.write(dialect, buffer);
    }
  }
}
