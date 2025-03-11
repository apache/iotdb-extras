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

import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.RateLimiter;
import java.util.concurrent.TimeUnit;

import org.apache.iotdb.collector.config.PipeOptions;

/** This is a global rate limiter for all connectors. */
public class GlobalRateLimiter {

  private final AtomicDouble throughputBytesPerSecond =
      new AtomicDouble(PipeOptions.PIPE_ALL_SINK_RATE_LIMIT_BYTES_PER_SECOND.value());
  private final RateLimiter rateLimiter;

  public GlobalRateLimiter() {
    final double throughputBytesPerSecondLimit = throughputBytesPerSecond.get();
    rateLimiter =
        throughputBytesPerSecondLimit <= 0
            ? RateLimiter.create(Double.MAX_VALUE)
            : RateLimiter.create(throughputBytesPerSecondLimit);
  }

  public void acquire(long bytes) {
    if (reloadParams()) {
      return;
    }

    while (bytes > 0) {
      if (bytes > Integer.MAX_VALUE) {
        tryAcquireWithRateCheck(Integer.MAX_VALUE);
        bytes -= Integer.MAX_VALUE;
      } else {
        tryAcquireWithRateCheck((int) bytes);
        return;
      }
    }
  }

  private void tryAcquireWithRateCheck(final int bytes) {
    while (!rateLimiter.tryAcquire(
        bytes,
        PipeOptions.RATE_LIMITER_HOT_RELOAD_CHECK_INTERVAL_MS.value(),
        TimeUnit.MILLISECONDS)) {
      if (reloadParams()) {
        return;
      }
    }
  }

  private boolean reloadParams() {
    final double throughputBytesPerSecondLimit =
        PipeOptions.PIPE_ALL_SINK_RATE_LIMIT_BYTES_PER_SECOND.value();

    if (throughputBytesPerSecond.get() != throughputBytesPerSecondLimit) {
      throughputBytesPerSecond.set(throughputBytesPerSecondLimit);
      rateLimiter.setRate(
          // if throughput <= 0, disable rate limiting
          throughputBytesPerSecondLimit <= 0 ? Double.MAX_VALUE : throughputBytesPerSecondLimit);
    }

    // For performance, we don't need to acquire rate limiter if throughput <= 0
    return throughputBytesPerSecondLimit <= 0;
  }
}
