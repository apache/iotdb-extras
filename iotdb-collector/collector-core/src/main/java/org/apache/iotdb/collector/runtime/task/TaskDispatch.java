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

package org.apache.iotdb.collector.runtime.task;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class TaskDispatch {

  private static final long CHECK_RUNNING_INTERVAL_NANOS = 100_000_000L;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicBoolean isDropped = new AtomicBoolean(false);

  public void resume() {
    isRunning.set(true);
  }

  public void pause() {
    isRunning.set(false);
  }

  public void remove() {
    pause();
    isDropped.set(true);
  }

  public boolean isRunning() {
    return isRunning.get();
  }

  public void waitUntilRunningOrDropped() {
    while (!isRunning.get() && !isDropped.get()) {
      LockSupport.parkNanos(CHECK_RUNNING_INTERVAL_NANOS);
    }
  }
}
