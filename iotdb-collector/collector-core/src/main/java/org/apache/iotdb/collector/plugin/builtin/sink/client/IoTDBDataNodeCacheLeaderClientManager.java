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

package org.apache.iotdb.collector.plugin.builtin.sink.client;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.util.concurrent.AtomicDouble;

import java.util.concurrent.ConcurrentHashMap;

public interface IoTDBDataNodeCacheLeaderClientManager {

  LeaderCacheManager LEADER_CACHE_MANAGER = new LeaderCacheManager();

  class LeaderCacheManager {

    private final AtomicDouble memoryUsageCheatFactor = new AtomicDouble(1);

    // leader cache built by LRU
    private final Cache<String, TEndPoint> device2endpoint;
    // a hashmap to reuse the created endpoint
    private final ConcurrentHashMap<TEndPoint, TEndPoint> endPoints = new ConcurrentHashMap<>();

    public LeaderCacheManager() {
      device2endpoint =
          Caffeine.newBuilder()
              .weigher(
                  (Weigher<String, TEndPoint>)
                      (device, endPoint) -> {
                        final long weightInLong =
                            (long) (device.getBytes().length * memoryUsageCheatFactor.get());
                        if (weightInLong <= 0) {
                          return Integer.MAX_VALUE;
                        }
                        final int weightInInt = (int) weightInLong;
                        return weightInInt != weightInLong ? Integer.MAX_VALUE : weightInInt;
                      })
              .recordStats()
              .build();
    }

    public TEndPoint getLeaderEndPoint(final String deviceId) {
      return deviceId == null ? null : device2endpoint.getIfPresent(deviceId);
    }

    public void updateLeaderEndPoint(final String deviceId, final TEndPoint endPoint) {
      if (deviceId == null || endPoint == null) {
        return;
      }

      final TEndPoint endPointFromMap = endPoints.putIfAbsent(endPoint, endPoint);
      if (endPointFromMap != null) {
        device2endpoint.put(deviceId, endPointFromMap);
      } else {
        device2endpoint.put(deviceId, endPoint);
      }
    }
  }
}
