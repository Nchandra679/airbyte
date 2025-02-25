/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.workers;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.string.Strings;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.ReplicationAttemptSummary;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncSummary.ReplicationStatus;
import io.airbyte.config.State;
import io.airbyte.config.WorkerDestinationConfig;
import io.airbyte.config.WorkerSourceConfig;
import io.airbyte.config.helpers.LogClientSingleton;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.validation.json.JsonSchemaValidator;
import io.airbyte.workers.protocols.airbyte.AirbyteDestination;
import io.airbyte.workers.protocols.airbyte.AirbyteMessageTracker;
import io.airbyte.workers.protocols.airbyte.AirbyteMessageUtils;
import io.airbyte.workers.protocols.airbyte.AirbyteSource;
import io.airbyte.workers.protocols.airbyte.NamespacingMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class DefaultReplicationWorkerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultReplicationWorkerTest.class);

  private static final String JOB_ID = "0";
  private static final int JOB_ATTEMPT = 0;
  private static final Path WORKSPACE_ROOT = Path.of("workspaces/10");
  private static final String STREAM_NAME = "user_preferences";
  private static final String FIELD_NAME = "favorite_color";
  private static final AirbyteMessage RECORD_MESSAGE1 = AirbyteMessageUtils.createRecordMessage(STREAM_NAME, FIELD_NAME, "blue");
  private static final AirbyteMessage RECORD_MESSAGE2 = AirbyteMessageUtils.createRecordMessage(STREAM_NAME, FIELD_NAME, "yellow");
  private static final AirbyteMessage STATE_MESSAGE = AirbyteMessageUtils.createStateMessage("checkpoint", "1");

  private Path jobRoot;
  private AirbyteSource source;
  private NamespacingMapper mapper;
  private AirbyteDestination destination;
  private StandardSyncInput syncInput;
  private WorkerSourceConfig sourceConfig;
  private WorkerDestinationConfig destinationConfig;
  private AirbyteMessageTracker sourceMessageTracker;
  private AirbyteMessageTracker destinationMessageTracker;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setup() throws Exception {
    MDC.clear();

    jobRoot = Files.createDirectories(Files.createTempDirectory("test").resolve(WORKSPACE_ROOT));

    final ImmutablePair<StandardSync, StandardSyncInput> syncPair = TestConfigHelpers.createSyncConfig();
    syncInput = syncPair.getValue();

    sourceConfig = WorkerUtils.syncToWorkerSourceConfig(syncInput);
    destinationConfig = WorkerUtils.syncToWorkerDestinationConfig(syncInput);

    source = mock(AirbyteSource.class);
    mapper = mock(NamespacingMapper.class);
    destination = mock(AirbyteDestination.class);
    sourceMessageTracker = mock(AirbyteMessageTracker.class);
    destinationMessageTracker = mock(AirbyteMessageTracker.class);

    when(source.isFinished()).thenReturn(false, false, false, true);
    when(destination.isFinished()).thenReturn(false, false, false, true);
    when(source.attemptRead()).thenReturn(Optional.of(RECORD_MESSAGE1), Optional.empty(), Optional.of(RECORD_MESSAGE2));
    when(mapper.mapCatalog(destinationConfig.getCatalog())).thenReturn(destinationConfig.getCatalog());
    when(mapper.mapMessage(RECORD_MESSAGE1)).thenReturn(RECORD_MESSAGE1);
    when(mapper.mapMessage(RECORD_MESSAGE2)).thenReturn(RECORD_MESSAGE2);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void test() throws Exception {
    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    worker.run(syncInput, jobRoot);

    verify(source).start(sourceConfig, jobRoot);
    verify(destination).start(destinationConfig, jobRoot);
    verify(destination).accept(RECORD_MESSAGE1);
    verify(destination).accept(RECORD_MESSAGE2);
    verify(source).close();
    verify(destination).close();
  }

  @Test
  void testLoggingInThreads() throws IOException, WorkerException {
    // set up the mdc so that actually log to a file, so that we can verify that file logging captures
    // threads.
    final Path jobRoot = Files.createTempDirectory(Path.of("/tmp"), "mdc_test");
    LogClientSingleton.setJobMdc(jobRoot);

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    worker.run(syncInput, jobRoot);

    final Path logPath = jobRoot.resolve(LogClientSingleton.LOG_FILENAME);
    final String logs = IOs.readFile(logPath);

    // make sure we get logs from the threads.
    assertTrue(logs.contains("Replication thread started."));
    assertTrue(logs.contains("Destination output thread started."));
  }

  @SuppressWarnings({"BusyWait"})
  @Test
  void testCancellation() throws InterruptedException {
    final AtomicReference<ReplicationOutput> output = new AtomicReference<>();
    when(source.isFinished()).thenReturn(false);
    when(destinationMessageTracker.getOutputState()).thenReturn(Optional.of(new State().withState(STATE_MESSAGE.getState().getData())));

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    final Thread workerThread = new Thread(() -> {
      try {
        output.set(worker.run(syncInput, jobRoot));
      } catch (WorkerException e) {
        throw new RuntimeException(e);
      }
    });

    workerThread.start();

    // verify the worker is actually running before we kill it.
    while (Mockito.mockingDetails(sourceMessageTracker).getInvocations().size() < 5) {
      LOGGER.info("waiting for worker to start running");
      sleep(100);
    }

    worker.cancel();
    Assertions.assertTimeout(Duration.ofSeconds(5), (Executable) workerThread::join);
    assertNotNull(output.get());
    assertEquals(output.get().getState().getState(), STATE_MESSAGE.getState().getData());
  }

  @Test
  void testPopulatesOutputOnSuccess() throws WorkerException {
    testPopulatesOutput();
  }

  @Test
  void testPopulatesStateOnFailureIfAvailable() throws Exception {
    doThrow(new IllegalStateException("induced exception")).when(source).close();
    when(destinationMessageTracker.getOutputState()).thenReturn(Optional.of(new State().withState(STATE_MESSAGE.getState().getData())));

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    final ReplicationOutput actual = worker.run(syncInput, jobRoot);
    assertNotNull(actual);
    assertEquals(STATE_MESSAGE.getState().getData(), actual.getState().getState());
  }

  @Test
  void testRetainsStateOnFailureIfNewStateNotAvailable() throws Exception {
    doThrow(new IllegalStateException("induced exception")).when(source).close();

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    final ReplicationOutput actual = worker.run(syncInput, jobRoot);

    assertNotNull(actual);
    assertEquals(syncInput.getState().getState(), actual.getState().getState());
  }

  @Test
  void testDoesNotPopulatesStateOnFailureIfNotAvailable() throws Exception {
    final StandardSyncInput syncInputWithoutState = Jsons.clone(syncInput);
    syncInputWithoutState.setState(null);

    doThrow(new IllegalStateException("induced exception")).when(source).close();

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    final ReplicationOutput actual = worker.run(syncInputWithoutState, jobRoot);

    assertNotNull(actual);
    assertNull(actual.getState());
  }

  @Test
  void testDoesNotPopulateOnIrrecoverableFailure() {
    doThrow(new IllegalStateException("induced exception")).when(sourceMessageTracker).getRecordCount();

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);
    assertThrows(WorkerException.class, () -> worker.run(syncInput, jobRoot));
  }

  private void testPopulatesOutput() throws WorkerException {
    final JsonNode expectedState = Jsons.jsonNode(ImmutableMap.of("updated_at", 10L));
    when(sourceMessageTracker.getRecordCount()).thenReturn(12L);
    when(sourceMessageTracker.getBytesCount()).thenReturn(100L);
    when(destinationMessageTracker.getOutputState()).thenReturn(Optional.of(new State().withState(expectedState)));

    final ReplicationWorker worker = new DefaultReplicationWorker(
        JOB_ID,
        JOB_ATTEMPT,
        source,
        mapper,
        destination,
        sourceMessageTracker,
        destinationMessageTracker);

    final ReplicationOutput actual = worker.run(syncInput, jobRoot);
    final ReplicationOutput replicationOutput = new ReplicationOutput()
        .withReplicationAttemptSummary(new ReplicationAttemptSummary()
            .withRecordsSynced(12L)
            .withBytesSynced(100L)
            .withStatus(ReplicationStatus.COMPLETED))
        .withOutputCatalog(syncInput.getCatalog())
        .withState(new State().withState(expectedState));

    // good enough to verify that times are present.
    assertNotNull(actual.getReplicationAttemptSummary().getStartTime());
    assertNotNull(actual.getReplicationAttemptSummary().getEndTime());

    // verify output object matches declared json schema spec.
    final Set<String> validate = new JsonSchemaValidator()
        .validate(Jsons.jsonNode(Jsons.jsonNode(JsonSchemaValidator.getSchema(ConfigSchema.REPLICATION_OUTPUT.getFile()))), Jsons.jsonNode(actual));
    assertTrue(validate.isEmpty(), "Validation errors: " + Strings.join(validate, ","));

    // remove times so we can do the rest of the object <> object comparison.
    actual.getReplicationAttemptSummary().withStartTime(null);
    actual.getReplicationAttemptSummary().withEndTime(null);

    assertEquals(replicationOutput, actual);
  }

}
