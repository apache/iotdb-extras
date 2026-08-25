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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;

public class MemoryManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(MemoryManager.class);

  /** The max retry times for memory allocation */
  private static final int MEMORY_ALLOCATE_MAX_RETRIES = 3;

  /** The retry interval for memory allocation */
  private static final long MEMORY_ALLOCATE_RETRY_INTERVAL_IN_MS = 1000;

  /** The min memory size to allocate */
  private static final long MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES = 32;

  /** The name of memory manager */
  private final String name;

  /** Whether memory management is enabled */
  private final boolean enabled;

  /** The total memory size in byte of memory manager */
  private volatile long totalMemorySizeInBytes;

  /** The allocated memory size */
  private volatile long allocatedMemorySizeInBytes = 0L;

  /** The parent memory manager */
  private final MemoryManager parentMemoryManager;

  /** The child memory manager */
  private final Map<String, MemoryManager> children = new ConcurrentHashMap<>();

  /** The allocated memory blocks of this memory manager */
  private final Map<String, IMemoryBlock> allocatedMemoryBlocks = new ConcurrentHashMap<>();

  MemoryManager(String name, MemoryManager parentMemoryManager, long totalMemorySizeInBytes) {
    this.name = name;
    this.parentMemoryManager = parentMemoryManager;
    this.totalMemorySizeInBytes = totalMemorySizeInBytes;
    this.enabled = false;
  }

  private MemoryManager(
      String name,
      MemoryManager parentMemoryManager,
      long totalMemorySizeInBytes,
      boolean enabled) {
    this.name = name;
    this.parentMemoryManager = parentMemoryManager;
    this.totalMemorySizeInBytes = totalMemorySizeInBytes;
    this.enabled = enabled;
  }

  // region The Methods Of IMemoryBlock Management

  /**
   * Allocate memory block with exact specified size in bytes
   *
   * @param name the name of memory block
   * @param sizeInBytes the size in bytes of memory block try to allocate
   * @param type the type of memory block
   * @return the allocated memory block
   * @throw MemoryException if fail to allocate after MEMORY_ALLOCATE_MAX_RETRIES retries
   */
  public synchronized IMemoryBlock exactAllocate(
      String name, long sizeInBytes, MemoryBlockType type) {
    if (!enabled) {
      return getOrRegisterMemoryBlock(name, sizeInBytes, type);
    }
    for (int i = 0; i < MEMORY_ALLOCATE_MAX_RETRIES; i++) {
      if (totalMemorySizeInBytes - allocatedMemorySizeInBytes >= sizeInBytes) {
        return getOrRegisterMemoryBlock(name, sizeInBytes, type);
      }

      try {
        this.wait(MEMORY_ALLOCATE_RETRY_INTERVAL_IN_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.warn("exactAllocate: interrupted while waiting for available memory", e);
      }
    }

    throw new MemoryException(
        String.format(
            "exactAllocate: failed to allocate memory after %d retries, "
                + "total memory size %d bytes, used memory size %d bytes, "
                + "requested memory size %d bytes",
            MEMORY_ALLOCATE_MAX_RETRIES,
            totalMemorySizeInBytes,
            allocatedMemorySizeInBytes,
            sizeInBytes));
  }

  /**
   * Try to allocate memory block with total memory size in bytes
   *
   * @param name the name of memory block
   * @param memoryBlockType the type of memory block
   */
  public synchronized IMemoryBlock exactAllocate(String name, MemoryBlockType memoryBlockType) {
    return exactAllocate(
        name, totalMemorySizeInBytes - allocatedMemorySizeInBytes, memoryBlockType);
  }

  /**
   * Try to allocate memory block with customAllocateStrategy
   *
   * @param name the name of memory block
   * @param sizeInBytes the size in bytes of memory block try to allocate
   * @param customAllocateStrategy the custom allocate strategy when memory is insufficient
   * @param type the type of memory block
   * @return the memory block if success, otherwise null
   */
  public synchronized IMemoryBlock tryAllocate(
      String name,
      long sizeInBytes,
      LongUnaryOperator customAllocateStrategy,
      MemoryBlockType type) {
    if (!enabled) {
      return getOrRegisterMemoryBlock(name, sizeInBytes, type);
    }

    if (totalMemorySizeInBytes - allocatedMemorySizeInBytes >= sizeInBytes) {
      return getOrRegisterMemoryBlock(name, sizeInBytes, type);
    }

    long sizeToAllocateInBytes = sizeInBytes;
    while (sizeToAllocateInBytes >= MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES) {
      if (totalMemorySizeInBytes - allocatedMemorySizeInBytes >= sizeToAllocateInBytes) {
        LOGGER.debug(
            "tryAllocate: allocated memory, "
                + "total memory size {} bytes, used memory size {} bytes, "
                + "original requested memory size {} bytes, "
                + "actual requested memory size {} bytes",
            totalMemorySizeInBytes,
            allocatedMemorySizeInBytes,
            sizeInBytes,
            sizeToAllocateInBytes);
        return getOrRegisterMemoryBlock(name, sizeToAllocateInBytes, type);
      }

      sizeToAllocateInBytes =
          Math.max(
              customAllocateStrategy.applyAsLong(sizeToAllocateInBytes),
              MEMORY_ALLOCATE_MIN_SIZE_IN_BYTES);
    }

    // TODO @spricoder: consider to find more memory in active way
    LOGGER.warn(
        "tryAllocate: failed to allocate memory, "
            + "total memory size {} bytes, used memory size {} bytes, "
            + "requested memory size {} bytes",
        totalMemorySizeInBytes,
        allocatedMemorySizeInBytes,
        sizeInBytes);
    return null;
  }

  /**
   * Try to register memory block with specified size in bytes
   *
   * @param name the name of memory block, UNIQUE
   * @param sizeInBytes the size in bytes of memory block
   * @param type the type of memory block
   * @return the memory block
   */
  private IMemoryBlock getOrRegisterMemoryBlock(
      String name, long sizeInBytes, MemoryBlockType type) {
    if (sizeInBytes < 0) {
      throw new MemoryException(
          String.format(
              "register memory block %s failed: sizeInBytes should be non-negative", name));
    }
    return allocatedMemoryBlocks.compute(
        name,
        (blockName, block) -> {
          if (block != null) {
            if (block.getTotalMemorySizeInBytes() != sizeInBytes) {
              LOGGER.warn(
                  "getOrRegisterMemoryBlock failed: memory block {} already exists, "
                      + "it's size is {}, requested size is {}",
                  blockName,
                  block.getTotalMemorySizeInBytes(),
                  sizeInBytes);
            }
            return block;
          } else {
            allocatedMemorySizeInBytes += sizeInBytes;
            return new AtomicLongMemoryBlock(name, this, sizeInBytes, type);
          }
        });
  }

  /**
   * Release memory block and notify all waiting threads
   *
   * @param block the memory block to release
   */
  public synchronized void release(IMemoryBlock block) {
    if (block == null || block.isReleased()) {
      return;
    }
    releaseWithOutNotify(block);
    this.notifyAll();
  }

  /**
   * Release memory block without notify
   *
   * @param block the memory block to release
   */
  public synchronized void releaseWithOutNotify(IMemoryBlock block) {
    if (block == null || block.isReleased()) {
      return;
    }

    block.markAsReleased();
    allocatedMemorySizeInBytes -= block.getTotalMemorySizeInBytes();
    allocatedMemoryBlocks.remove(block.getName());
    try {
      block.close();
    } catch (Exception e) {
      LOGGER.error("releaseWithOutNotify: failed to close memory block {}", block, e);
    }
  }

  // endregion

  // region The Methods Of MemoryManager Management

  /**
   * Try to create a new memory manager with specified name and total memory size in bytes, then put
   * it into children map. NOTICE: if there are same name memory manager, it will return the
   * existing one instead of creating a new one or update the existing one.
   *
   * @param name the name of memory manager
   * @param sizeInBytes the total memory size in bytes of memory manager
   * @param enabled whether memory management is enabled
   * @return the memory manager
   */
  public synchronized MemoryManager getOrCreateMemoryManager(
      String name, long sizeInBytes, boolean enabled) {
    return children.compute(
        name,
        (managerName, manager) -> {
          if (sizeInBytes < 0) {
            LOGGER.warn("getOrCreateMemoryManager {}: sizeInBytes should be positive", name);
            return null;
          }
          if (manager != null) {
            LOGGER.debug(
                "getMemoryManager: memory manager {} already exists, it's size is {}, enabled is {}",
                managerName,
                manager.getTotalMemorySizeInBytes(),
                manager.isEnable());
            return manager;
          } else {
            if (this.enabled
                && sizeInBytes + this.allocatedMemorySizeInBytes > this.totalMemorySizeInBytes) {
              LOGGER.warn(
                  "getOrCreateMemoryManager failed: total memory size {} bytes is less than allocated memory size {} bytes",
                  sizeInBytes,
                  allocatedMemorySizeInBytes);
              return null;
            }
            allocatedMemorySizeInBytes += sizeInBytes;
            return new MemoryManager(name, this, sizeInBytes, enabled);
          }
        });
  }

  /**
   * Try to create a new memory manager with specified name and total memory size in bytes, then put
   * it into children map. NOTICE: if there are same name memory manager, it will return the
   * existing one.
   *
   * @param name the name of memory manager
   * @param totalMemorySizeInBytes the total memory size in bytes of memory manager
   * @return the memory manager
   */
  public synchronized MemoryManager getOrCreateMemoryManager(
      String name, long totalMemorySizeInBytes) {
    return getOrCreateMemoryManager(name, totalMemorySizeInBytes, false);
  }

  /**
   * Re-allocate memory according to ratio
   *
   * @param ratio the ratio of new total memory size to old total memory size
   */
  private void reAllocateMemoryAccordingToRatio(double ratio) {
    // first increase the total memory size of this memory manager
    this.totalMemorySizeInBytes *= ratio;
    // then re-allocate memory for all memory blocks
    for (IMemoryBlock block : allocatedMemoryBlocks.values()) {
      block.setTotalMemorySizeInBytes((long) (block.getTotalMemorySizeInBytes() * ratio));
    }
    // finally re-allocate memory for all child memory managers
    for (Map.Entry<String, MemoryManager> entry : children.entrySet()) {
      entry.getValue().reAllocateMemoryAccordingToRatio(ratio);
    }
  }

  public String getName() {
    return name;
  }

  public boolean isEnable() {
    return enabled;
  }

  /** Get total memory size in bytes of memory manager */
  public long getTotalMemorySizeInBytes() {
    return totalMemorySizeInBytes;
  }

  public void setTotalMemorySizeInBytes(long totalMemorySizeInBytes) {
    this.totalMemorySizeInBytes = totalMemorySizeInBytes;
  }

  public void setTotalMemorySizeInBytesWithReload(long totalMemorySizeInBytes) {
    reAllocateMemoryAccordingToRatio((double) totalMemorySizeInBytes / this.totalMemorySizeInBytes);
  }

  /** Get available memory size in bytes of memory manager */
  public long getAvailableMemorySizeInBytes() {
    return totalMemorySizeInBytes - allocatedMemorySizeInBytes;
  }

  /** Get allocated memory size in bytes of memory manager */
  public long getAllocatedMemorySizeInBytes() {
    return allocatedMemorySizeInBytes;
  }

  /** Get actual used memory size in bytes of memory manager */
  public long getUsedMemorySizeInBytes() {
    long memorySize =
        allocatedMemoryBlocks.values().stream().mapToLong(IMemoryBlock::getUsedMemoryInBytes).sum();
    for (MemoryManager child : children.values()) {
      memorySize += child.getUsedMemorySizeInBytes();
    }
    return memorySize;
  }

  // endregion

  @Override
  public String toString() {
    return "MemoryManager{"
        + "name="
        + name
        + ", enabled="
        + enabled
        + ", totalMemorySizeInBytes="
        + totalMemorySizeInBytes
        + ", allocatedMemorySizeInBytes="
        + allocatedMemorySizeInBytes
        + '}';
  }

  public void print() {
    print(0);
  }

  private void print(int indent) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < indent; i++) {
      sb.append("  ");
    }
    sb.append(this);
    LOGGER.info(sb.toString());
    for (IMemoryBlock block : allocatedMemoryBlocks.values()) {
      block.print(indent + 2);
    }
    for (MemoryManager child : children.values()) {
      child.print(indent + 1);
    }
  }
}
