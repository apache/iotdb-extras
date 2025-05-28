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

import org.apache.iotdb.collector.config.PipeRuntimeOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongUnaryOperator;

public class PipeMemoryManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeMemoryManager.class);

  private static final boolean PIPE_MEMORY_MANAGEMENT_ENABLED =
      PipeRuntimeOptions.PIPE_MEMORY_MANAGEMENT_ENABLED.value();

  private static final long MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES =
      PipeRuntimeOptions.PIPE_MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES.value();

  private final IMemoryBlock memoryBlock =
      new MemoryManager("pipeMemoryManager", null, Runtime.getRuntime().totalMemory() / 10)
          .exactAllocate("Stream", MemoryBlockType.DYNAMIC);

  private static final double FLOATING_MEMORY_RATIO =
      PipeRuntimeOptions.PIPE_TOTAL_FLOATING_MEMORY_PROPORTION.value();

  // Only non-zero memory blocks will be added to this set.
  private final Set<PipeMemoryBlock> allocatedBlocks = new HashSet<>();

  private PipeMemoryManager() {}

  public synchronized PipeMemoryBlock tryAllocate(long sizeInBytes) {
    return tryAllocate(sizeInBytes, currentSize -> currentSize * 2 / 3);
  }

  public synchronized PipeMemoryBlock tryAllocate(
      long sizeInBytes, LongUnaryOperator customAllocateStrategy) {
    if (!PIPE_MEMORY_MANAGEMENT_ENABLED) {
      return new PipeMemoryBlock(sizeInBytes);
    }

    if (sizeInBytes == 0
        || getTotalNonFloatingMemorySizeInBytes() - memoryBlock.getUsedMemoryInBytes()
            >= sizeInBytes) {
      return registerMemoryBlock(sizeInBytes);
    }

    long sizeToAllocateInBytes = sizeInBytes;
    while (sizeToAllocateInBytes > MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES) {
      if (getTotalNonFloatingMemorySizeInBytes() - memoryBlock.getUsedMemoryInBytes()
          >= sizeToAllocateInBytes) {
        LOGGER.info(
            "tryAllocate: allocated memory, "
                + "total memory size {} bytes, used memory size {} bytes, "
                + "original requested memory size {} bytes, "
                + "actual requested memory size {} bytes",
            getTotalNonFloatingMemorySizeInBytes(),
            memoryBlock.getUsedMemoryInBytes(),
            sizeInBytes,
            sizeToAllocateInBytes);
        return registerMemoryBlock(sizeToAllocateInBytes);
      }

      sizeToAllocateInBytes =
          Math.max(
              customAllocateStrategy.applyAsLong(sizeToAllocateInBytes),
              MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES);
    }

    if (tryShrinkUntilFreeMemorySatisfy(sizeToAllocateInBytes)) {
      LOGGER.info(
          "tryAllocate: allocated memory, "
              + "total memory size {} bytes, used memory size {} bytes, "
              + "original requested memory size {} bytes, "
              + "actual requested memory size {} bytes",
          getTotalNonFloatingMemorySizeInBytes(),
          memoryBlock.getUsedMemoryInBytes(),
          sizeInBytes,
          sizeToAllocateInBytes);
      return registerMemoryBlock(sizeToAllocateInBytes);
    } else {
      LOGGER.warn(
          "tryAllocate: failed to allocate memory, "
              + "total memory size {} bytes, used memory size {} bytes, "
              + "requested memory size {} bytes",
          getTotalNonFloatingMemorySizeInBytes(),
          memoryBlock.getUsedMemoryInBytes(),
          sizeInBytes);
      return registerMemoryBlock(0);
    }
  }

  private PipeMemoryBlock registerMemoryBlock(long sizeInBytes) {
    // For memory block whose size is 0, we do not need to add it to the allocated blocks now.
    // It's good for performance and will not trigger concurrent issues.
    // If forceResize is called on it, we will add it to the allocated blocks.
    final PipeMemoryBlock returnedMemoryBlock = new PipeMemoryBlock(sizeInBytes);

    if (sizeInBytes > 0) {
      memoryBlock.forceAllocateWithoutLimitation(sizeInBytes);
      allocatedBlocks.add(returnedMemoryBlock);
    }

    return returnedMemoryBlock;
  }

  private boolean tryShrinkUntilFreeMemorySatisfy(long sizeInBytes) {
    final List<PipeMemoryBlock> shuffledBlocks = new ArrayList<>(allocatedBlocks);
    Collections.shuffle(shuffledBlocks);

    while (true) {
      boolean hasAtLeastOneBlockShrinkable = false;
      for (final PipeMemoryBlock block : shuffledBlocks) {
        if (block.shrink()) {
          hasAtLeastOneBlockShrinkable = true;
          if (getTotalNonFloatingMemorySizeInBytes() - memoryBlock.getUsedMemoryInBytes()
              >= sizeInBytes) {
            return true;
          }
        }
      }
      if (!hasAtLeastOneBlockShrinkable) {
        return false;
      }
    }
  }

  public long getTotalNonFloatingMemorySizeInBytes() {
    return (long) (memoryBlock.getTotalMemorySizeInBytes() * (1 - FLOATING_MEMORY_RATIO));
  }

  public static PipeMemoryManager getInstance() {
    return PipeMemoryManagerHolder.INSTANCE;
  }

  private static class PipeMemoryManagerHolder {
    private static final PipeMemoryManager INSTANCE = new PipeMemoryManager();
  }
}
