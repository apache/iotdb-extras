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

import org.apache.iotdb.collector.config.TaskRuntimeOptions;
import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.collector.plugin.api.PushSource;
import org.apache.iotdb.collector.plugin.builtin.processor.DoNothingProcessor;
import org.apache.iotdb.collector.runtime.plugin.PluginRuntime;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.collector.runtime.task.processor.ProcessorTask;
import org.apache.iotdb.collector.runtime.task.sink.SinkTask;
import org.apache.iotdb.collector.runtime.task.source.SourceTask;
import org.apache.iotdb.collector.runtime.task.source.pull.PullSourceTask;
import org.apache.iotdb.collector.runtime.task.source.push.PushSourceTask;
import org.apache.iotdb.collector.service.RuntimeService;
import org.apache.iotdb.collector.service.ScheduleService;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.PipeSink;
import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeConnectorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSinkRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import com.lmax.disruptor.WorkProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TaskCombinerTest {

  private final ScheduleService scheduleService = new ScheduleService();
  private final FakePlugins plugins = new FakePlugins();
  private PluginRuntime previousPlugins;

  @Before
  public void setUp() throws Exception {
    scheduleService.start();
    previousPlugins = pluginRuntime().getAndSet(plugins);
  }

  @After
  public void tearDown() throws Exception {
    pluginRuntime().set(previousPlugins);
    scheduleService.stop();
  }

  @Test
  public void failedPushSourceReleasesEveryStageSoTheTaskCanBeRecreated() throws Exception {
    final FakePushSource started = new FakePushSource(null, false);
    final FakePushSource failing = new FakePushSource(new IllegalStateException("no topic"), false);
    plugins.sources.add(started);
    plugins.sources.add(failing);

    try {
      combiner("push-rollback", 2, false).create();
      fail("task creation must fail when a source cannot start");
    } catch (final IllegalStateException e) {
      assertEquals("no topic", e.getMessage());
    }
    assertTrue("a source started before the failure must be closed", started.closed);
    assertTrue(failing.closed);
    assertEquals(
        TaskRuntimeOptions.TASK_SINK_PARALLELISM_NUM.value().intValue(), plugins.closedSinks.get());
    assertNoDisruptorWorkers();

    // Without the rollback, the retry reuses the failed attempt's executors while their threads
    // still run its workers, so the event below would never reach the sink.
    plugins.sources.add(new FakePushSource(null, true));
    final TaskCombiner retried = combiner("push-rollback", 1, false);
    retried.create();
    try {
      assertTrue(
          "a retry under the same task id must deliver",
          plugins.delivered.await(10, TimeUnit.SECONDS));
    } finally {
      retried.drop();
    }
    assertNoDisruptorWorkers();
  }

  @Test
  public void failedPullSourceStopsTheLoopsAlreadyRunning() throws Exception {
    final FakePullSource polling = new FakePullSource(null, null);
    final FakePullSource failing =
        new FakePullSource(new IllegalStateException("unreachable"), polling.firstPoll);
    plugins.sources.add(polling);
    plugins.sources.add(failing);

    try {
      combiner("pull-rollback", 2, true).create();
      fail("task creation must fail when a source cannot start");
    } catch (final IllegalStateException e) {
      assertEquals("unreachable", e.getMessage());
    }
    assertTrue(polling.closed);
    assertTrue(failing.closed);
    assertNoDisruptorWorkers();

    // The failing source only fails once the first loop has polled, so a loop was running; the
    // rollback waits for it to return before closing the source.
    final int polls = polling.polls.get();
    assertTrue(polls > 0);
    Thread.sleep(200);
    assertEquals("the loop must stop polling a closed source", polls, polling.polls.get());
  }

  @Test
  public void dropDrainsABackloggedTaskWithoutWaitingForTheTimeout() throws Exception {
    plugins.transferDelayMs = 1;
    final FloodingPushSource flooding = new FloodingPushSource();
    plugins.sources.add(flooding);
    final TaskCombiner combiner = combiner("backlog-drop", 1, false);
    combiner.create();
    // Both ring buffers (1024 slots each) are full behind the slow sink once this many are out.
    assertTrue(flooding.backlogged.await(10, TimeUnit.SECONDS));

    // Draining the processor used to wait for its timeout: the paused sink did not consume.
    final long start = System.nanoTime();
    combiner.drop();
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue("drop took " + elapsedMs + " ms", elapsedMs < 5_000);
    assertTrue(flooding.closed);
    assertNoDisruptorWorkers();
  }

  /**
   * A halt that reaches a disruptor worker before it runs is lost, so a quick creation failure used
   * to leave sink and processor workers parked forever.
   */
  private static void assertNoDisruptorWorkers() {
    for (final Map.Entry<Thread, StackTraceElement[]> thread :
        Thread.getAllStackTraces().entrySet()) {
      for (final StackTraceElement frame : thread.getValue()) {
        assertFalse(
            thread.getKey().getName() + " still runs a disruptor worker",
            WorkProcessor.class.getName().equals(frame.getClassName()));
      }
    }
  }

  private static TaskCombiner combiner(
      final String taskId, final int sourceParallelism, final boolean pull) {
    final SinkTask sink = new SinkTask(taskId, new HashMap<>());
    final ProcessorTask processor = new ProcessorTask(taskId, new HashMap<>(), sink.makeProducer());
    final Map<String, String> sourceAttributes = new HashMap<>();
    sourceAttributes.put(
        TaskRuntimeOptions.TASK_SOURCE_PARALLELISM_NUM.key(), String.valueOf(sourceParallelism));
    final SourceTask source =
        pull
            ? new PullSourceTask(
                taskId, sourceAttributes, processor.makeProducer(), TaskStateEnum.RUNNING)
            : new PushSourceTask(
                taskId, sourceAttributes, processor.makeProducer(), TaskStateEnum.RUNNING);
    return new TaskCombiner(source, processor, sink);
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<PluginRuntime> pluginRuntime()
      throws ReflectiveOperationException {
    // RuntimeService offers no way to install a plugin runtime other than starting the service.
    final Field field = RuntimeService.class.getDeclaredField("PLUGIN");
    field.setAccessible(true);
    return (AtomicReference<PluginRuntime>) field.get(null);
  }

  private static final class FakePlugins extends PluginRuntime {

    private final Queue<PipeSource> sources = new ConcurrentLinkedQueue<>();
    private final AtomicInteger closedSinks = new AtomicInteger();
    private final CountDownLatch delivered = new CountDownLatch(1);
    private volatile long transferDelayMs;

    @Override
    public PipeSource constructSource(final PipeParameters sourceParameters) {
      return sources.remove();
    }

    @Override
    public PipeProcessor constructProcessor(final PipeParameters processorParameters) {
      return new DoNothingProcessor();
    }

    @Override
    public PipeSink constructSink(final PipeParameters sinkParameters) {
      return new FakeSink(this);
    }
  }

  private static final class MarkerEvent implements Event {}

  private static final class FakePushSource extends PushSource {

    private final Exception startFailure;
    private final boolean emit;
    private volatile boolean closed;

    private FakePushSource(final Exception startFailure, final boolean emit) {
      this.startFailure = startFailure;
      this.emit = emit;
    }

    @Override
    public void validate(final PipeParameterValidator validator) {}

    @Override
    public void customize(
        final PipeParameters parameters, final PipeSourceRuntimeConfiguration configuration) {}

    @Override
    public void start() throws Exception {
      if (startFailure != null) {
        throw startFailure;
      }
      if (emit) {
        supply(new MarkerEvent());
      }
    }

    @Override
    public Optional<ProgressIndex> report() {
      return Optional.empty();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class FloodingPushSource extends PushSource {

    private final CountDownLatch backlogged = new CountDownLatch(1);
    private volatile boolean closed;
    private Thread worker;

    @Override
    public void validate(final PipeParameterValidator validator) {}

    @Override
    public void customize(
        final PipeParameters parameters, final PipeSourceRuntimeConfiguration configuration) {}

    @Override
    public void start() {
      worker =
          new Thread(
              () -> {
                try {
                  for (int published = 1; !closed; published++) {
                    supply(new Event() {});
                    if (published == 3_000) {
                      backlogged.countDown();
                    }
                  }
                } catch (final Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              "flooding-push-source");
      worker.start();
    }

    @Override
    public Optional<ProgressIndex> report() {
      return Optional.empty();
    }

    @Override
    public void close() throws InterruptedException {
      closed = true;
      worker.join(10_000);
    }
  }

  private static final class FakePullSource extends PullSource {

    private final Exception startFailure;
    private final CountDownLatch failAfter;
    private final CountDownLatch firstPoll = new CountDownLatch(1);
    private final AtomicInteger polls = new AtomicInteger();
    private volatile boolean closed;

    private FakePullSource(final Exception startFailure, final CountDownLatch failAfter) {
      this.startFailure = startFailure;
      this.failAfter = failAfter;
    }

    @Override
    public void validate(final PipeParameterValidator validator) {}

    @Override
    public void customize(
        final PipeParameters parameters, final PipeSourceRuntimeConfiguration configuration) {}

    @Override
    public void start() throws Exception {
      if (startFailure != null) {
        failAfter.await(10, TimeUnit.SECONDS);
        throw startFailure;
      }
    }

    @Override
    public Event supply() throws InterruptedException {
      polls.incrementAndGet();
      firstPoll.countDown();
      Thread.sleep(5);
      return null;
    }

    @Override
    public Optional<ProgressIndex> report() {
      return Optional.empty();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class FakeSink implements PipeSink {

    private final FakePlugins plugins;

    private FakeSink(final FakePlugins plugins) {
      this.plugins = plugins;
    }

    @Override
    public void validate(final PipeParameterValidator validator) {}

    @Override
    public void customize(
        final PipeParameters parameters, final PipeConnectorRuntimeConfiguration configuration) {}

    @Override
    public void customize(
        final PipeParameters parameters, final PipeSinkRuntimeConfiguration configuration) {}

    @Override
    public void handshake() {}

    @Override
    public void heartbeat() {}

    @Override
    public void transfer(final TabletInsertionEvent tabletInsertionEvent) {}

    @Override
    public void transfer(final Event event) throws InterruptedException {
      if (plugins.transferDelayMs > 0) {
        Thread.sleep(plugins.transferDelayMs);
      }
      if (event instanceof MarkerEvent) {
        plugins.delivered.countDown();
      }
    }

    @Override
    public void close() {
      plugins.closedSinks.incrementAndGet();
    }
  }
}
