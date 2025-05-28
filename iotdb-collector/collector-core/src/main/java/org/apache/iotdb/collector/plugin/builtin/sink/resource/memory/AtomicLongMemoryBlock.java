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

package org.apache.iotdb.collector.plugin.builtin.sink.resource.memory;

import java.util.concurrent.atomic.AtomicLong;

public class AtomicLongMemoryBlock extends IMemoryBlock {
  /** The memory usage in byte of this memory block */
  protected final AtomicLong usedMemoryInBytes = new AtomicLong(0);

  public AtomicLongMemoryBlock(
      final String name,
      final MemoryManager memoryManager,
      final long maxMemorySizeInByte,
      final MemoryBlockType memoryBlockType) {
    this.name = name;
    this.memoryManager = memoryManager;
    this.totalMemorySizeInBytes = maxMemorySizeInByte;
    this.memoryBlockType = memoryBlockType;
  }

  @Override
  public void forceAllocateWithoutLimitation(long sizeInByte) {
    usedMemoryInBytes.addAndGet(sizeInByte);
  }

  public long getUsedMemoryInBytes() {
    return usedMemoryInBytes.get();
  }

  @Override
  public String toString() {
    return "IoTDBMemoryBlock{"
        + "name="
        + name
        + ", isReleased="
        + isReleased
        + ", memoryBlockType="
        + memoryBlockType
        + ", totalMemorySizeInBytes="
        + totalMemorySizeInBytes
        + ", usedMemoryInBytes="
        + usedMemoryInBytes
        + '}';
  }

  @Override
  public void close() throws Exception {
    if (memoryManager != null) {
      memoryManager.release(this);
    }
  }
}
