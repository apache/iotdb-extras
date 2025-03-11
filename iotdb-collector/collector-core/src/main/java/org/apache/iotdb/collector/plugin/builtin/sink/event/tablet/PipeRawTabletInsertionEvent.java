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

package org.apache.iotdb.collector.plugin.builtin.sink.event.tablet;

import org.apache.iotdb.collector.plugin.builtin.sink.datastructure.pattern.TablePattern;
import org.apache.iotdb.collector.plugin.builtin.sink.datastructure.pattern.TreePattern;
import org.apache.iotdb.collector.plugin.builtin.sink.event.tablet.parser.TabletInsertionEventParser;
import org.apache.iotdb.collector.plugin.builtin.sink.event.tablet.parser.TabletInsertionEventTablePatternParser;
import org.apache.iotdb.collector.plugin.builtin.sink.event.tablet.parser.TabletInsertionEventTreePatternParser;
import org.apache.iotdb.collector.plugin.builtin.sink.resource.ref.PipePhantomReferenceManager;
import org.apache.iotdb.pipe.api.access.Row;
import org.apache.iotdb.pipe.api.collector.RowCollector;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.tsfile.utils.RamUsageEstimator;
import org.apache.tsfile.write.record.Tablet;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class PipeRawTabletInsertionEvent extends PipeInsertionEvent
    implements TabletInsertionEvent, ReferenceTrackableEvent, AutoCloseable {

  // For better calculation
  private static final long INSTANCE_SIZE =
      RamUsageEstimator.shallowSizeOfInstance(PipeRawTabletInsertionEvent.class);
  private Tablet tablet;
  private String deviceId; // Only used when the tablet is released.
  private final boolean isAligned;

  private final PipeRawTabletInsertionEvent sourceEvent;
  private boolean needToReport;

  // private final PipeTabletMemoryBlock allocatedMemoryBlock;

  private TabletInsertionEventParser eventParser;

  private PipeRawTabletInsertionEvent(
      final Boolean isTableModelEvent,
      final String databaseName,
      final String tableModelDataBaseName,
      final String treeModelDataBaseName,
      final Tablet tablet,
      final boolean isAligned,
      final PipeRawTabletInsertionEvent sourceEvent,
      final boolean needToReport,
      final String pipeName,
      final long creationTime,
      final TreePattern treePattern,
      final TablePattern tablePattern,
      final long startTime,
      final long endTime) {
    super(
        pipeName,
        creationTime,
        treePattern,
        tablePattern,
        startTime,
        endTime,
        isTableModelEvent,
        databaseName,
        tableModelDataBaseName,
        treeModelDataBaseName);
    this.tablet = Objects.requireNonNull(tablet);
    this.isAligned = isAligned;
    this.sourceEvent = sourceEvent;
    this.needToReport = needToReport;

    // Allocate empty memory block, will be resized later.
    // this.allocatedMemoryBlock =
    //     PipeDataNodeResourceManager.memory().forceAllocateForTabletWithRetry(0);
  }

  public PipeRawTabletInsertionEvent(
      final Boolean isTableModelEvent,
      final String databaseName,
      final String tableModelDataBaseName,
      final String treeModelDataBaseName,
      final Tablet tablet,
      final boolean isAligned,
      final String pipeName,
      final long creationTime,
      final PipeRawTabletInsertionEvent sourceEvent,
      final boolean needToReport) {
    this(
        isTableModelEvent,
        databaseName,
        tableModelDataBaseName,
        treeModelDataBaseName,
        tablet,
        isAligned,
        sourceEvent,
        needToReport,
        pipeName,
        creationTime,
        null,
        null,
        Long.MIN_VALUE,
        Long.MAX_VALUE);
  }

  @Override
  public boolean internallyIncreaseResourceReferenceCount(final String holderMessage) {
    // PipeDataNodeResourceManager.memory()
    //     .forceResize(
    //         allocatedMemoryBlock,
    //         PipeMemoryWeightUtil.calculateTabletSizeInBytes(tablet) + INSTANCE_SIZE);
    return true;
  }

  @Override
  public boolean internallyDecreaseResourceReferenceCount(final String holderMessage) {
    // allocatedMemoryBlock.close();

    // Record the deviceId before the memory is released,
    // for later possibly updating the leader cache.
    deviceId = tablet.getDeviceId();

    // Actually release the occupied memory.
    tablet = null;
    eventParser = null;
    return true;
  }

  @Override
  public PipeRawTabletInsertionEvent shallowCopySelfAndBindPipeTaskMetaForProgressReport(
      final String pipeName,
      final long creationTime,
      final TreePattern treePattern,
      final TablePattern tablePattern,
      final long startTime,
      final long endTime) {
    return new PipeRawTabletInsertionEvent(
        getRawIsTableModelEvent(),
        getSourceDatabaseNameFromDataRegion(),
        getRawTableModelDataBase(),
        getRawTreeModelDataBase(),
        tablet,
        isAligned,
        sourceEvent,
        needToReport,
        pipeName,
        creationTime,
        treePattern,
        tablePattern,
        startTime,
        endTime);
  }

  @Override
  public boolean isGeneratedByPipe() {
    throw new UnsupportedOperationException("isGeneratedByPipe() is not supported!");
  }

  @Override
  public boolean mayEventTimeOverlappedWithTimeRange() {
    final long[] timestamps = tablet.getTimestamps();
    if (Objects.isNull(timestamps) || timestamps.length == 0) {
      return false;
    }
    // We assume that `timestamps` is ordered.
    return startTime <= timestamps[timestamps.length - 1] && timestamps[0] <= endTime;
  }

  @Override
  public boolean mayEventPathsOverlappedWithPattern() {
    return sourceEvent == null || sourceEvent.mayEventPathsOverlappedWithPattern();
  }

  public void markAsNeedToReport() {
    this.needToReport = true;
  }

  public String getDeviceId() {
    // NonNull indicates that the internallyDecreaseResourceReferenceCount has not been called.
    return Objects.nonNull(tablet) ? tablet.getDeviceId() : deviceId;
  }

  public PipeRawTabletInsertionEvent getSourceEvent() {
    return sourceEvent;
  }

  /////////////////////////// TabletInsertionEvent ///////////////////////////

  @Override
  public Iterable<TabletInsertionEvent> processRowByRow(
      final BiConsumer<Row, RowCollector> consumer) {
    return initEventParser().processRowByRow(consumer);
  }

  @Override
  public Iterable<TabletInsertionEvent> processTablet(
      final BiConsumer<Tablet, RowCollector> consumer) {
    return initEventParser().processTablet(consumer);
  }

  /////////////////////////// convertToTablet ///////////////////////////

  public boolean isAligned() {
    return isAligned;
  }

  public Tablet convertToTablet() {
    if (!shouldParseTimeOrPattern()) {
      return tablet;
    }
    return initEventParser().convertToTablet();
  }

  /////////////////////////// event parser ///////////////////////////

  private TabletInsertionEventParser initEventParser() {
    if (eventParser == null) {
      eventParser =
          tablet.getDeviceId().startsWith("root.")
              ? new TabletInsertionEventTreePatternParser(this, tablet, isAligned, treePattern)
              : new TabletInsertionEventTablePatternParser(this, tablet, isAligned, tablePattern);
    }
    return eventParser;
  }

  public long count() {
    final Tablet covertedTablet = shouldParseTimeOrPattern() ? convertToTablet() : tablet;
    return (long) covertedTablet.getRowSize() * covertedTablet.getSchemas().size();
  }

  /////////////////////////// parsePatternOrTime ///////////////////////////

  public PipeRawTabletInsertionEvent parseEventWithPatternOrTime() {
    return new PipeRawTabletInsertionEvent(
        getRawIsTableModelEvent(),
        getSourceDatabaseNameFromDataRegion(),
        getRawTableModelDataBase(),
        getRawTreeModelDataBase(),
        convertToTablet(),
        isAligned,
        pipeName,
        creationTime,
        this,
        needToReport);
  }

  public boolean hasNoNeedParsingAndIsEmpty() {
    return !shouldParseTimeOrPattern() && isTabletEmpty(tablet);
  }

  public static boolean isTabletEmpty(final Tablet tablet) {
    return Objects.isNull(tablet)
        || tablet.getRowSize() == 0
        || Objects.isNull(tablet.getSchemas())
        || tablet.getSchemas().isEmpty();
  }

  /////////////////////////// Object ///////////////////////////

  @Override
  public String toString() {
    return String.format(
            "PipeRawTabletInsertionEvent{tablet=%s, isAligned=%s, sourceEvent=%s, needToReport=%s, eventParser=%s}",
            tablet, isAligned, sourceEvent, needToReport, eventParser)
        + " - "
        + super.toString();
  }

  @Override
  public String coreReportMessage() {
    return String.format(
            "PipeRawTabletInsertionEvent{tablet=%s, isAligned=%s, sourceEvent=%s, needToReport=%s}",
            tablet,
            isAligned,
            sourceEvent == null ? "null" : sourceEvent.coreReportMessage(),
            needToReport)
        + " - "
        + super.coreReportMessage();
  }

  /////////////////////////// ReferenceTrackableEvent ///////////////////////////

  @Override
  protected void trackResource() {
    // PipeDataNodeResourceManager.ref().trackPipeEventResource(this, eventResourceBuilder());
  }

  @Override
  public PipePhantomReferenceManager.PipeEventResource eventResourceBuilder() {
    return new PipeRawTabletInsertionEventResource(this.isReleased, this.referenceCount);
  }

  private static class PipeRawTabletInsertionEventResource
      extends PipePhantomReferenceManager.PipeEventResource {

    // private final PipeTabletMemoryBlock allocatedMemoryBlock;

    private PipeRawTabletInsertionEventResource(
        final AtomicBoolean isReleased, final AtomicInteger referenceCount) {
      super(isReleased, referenceCount);
    }

    @Override
    protected void finalizeResource() {
      // allocatedMemoryBlock.close();
    }
  }

  /////////////////////////// AutoCloseable ///////////////////////////

  @Override
  public void close() {
    // The semantic of close is to release the memory occupied by parsing, this method does nothing
    // to unify the external close semantic:
    //   1. PipeRawTabletInsertionEvent: the tablet occupying memory upon construction, even when
    // parsing is involved.
    //   2. PipeInsertNodeTabletInsertionEvent: the tablet is only constructed when it's actually
    // involved in parsing.
  }
}
