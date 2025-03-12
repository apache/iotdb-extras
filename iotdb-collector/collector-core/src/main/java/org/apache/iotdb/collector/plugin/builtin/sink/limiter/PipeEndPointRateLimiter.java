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

package org.apache.iotdb.collector.plugin.builtin.sink.limiter;

import org.apache.iotdb.collector.config.PipeOptions;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;

import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class PipeEndPointRateLimiter {

  // The task agent is used to check if the pipe is still alive

  // private final String pipeName;
  // private final long creationTime;

  private final double bytesPerSecondLimit;

  private final ConcurrentMap<TEndPoint, RateLimiter> endPointRateLimiterMap;

  public PipeEndPointRateLimiter(
      final String pipeName, final long creationTime, final double bytesPerSecondLimit) {
    // this.pipeName = pipeName;
    // this.creationTime = creationTime;
    this.bytesPerSecondLimit = bytesPerSecondLimit;
    endPointRateLimiterMap = new ConcurrentHashMap<>();
  }

  public void acquire(final TEndPoint endPoint, long bytes) {
    if (endPoint == null) {
      return;
    }

    final RateLimiter rateLimiter =
        endPointRateLimiterMap.computeIfAbsent(
            endPoint, e -> RateLimiter.create(bytesPerSecondLimit));

    while (bytes > 0) {
      if (bytes > Integer.MAX_VALUE) {
        if (!tryAcquireWithPipeCheck(rateLimiter, Integer.MAX_VALUE)) {
          return;
        }
        bytes -= Integer.MAX_VALUE;
      } else {
        tryAcquireWithPipeCheck(rateLimiter, (int) bytes);
        return;
      }
    }
  }

  private boolean tryAcquireWithPipeCheck(final RateLimiter rateLimiter, final int bytes) {
    while (!rateLimiter.tryAcquire(
        bytes,
        PipeOptions.RATE_LIMITER_HOT_RELOAD_CHECK_INTERVAL_MS.value(),
        TimeUnit.MILLISECONDS)) {}
    return true;
  }
}
