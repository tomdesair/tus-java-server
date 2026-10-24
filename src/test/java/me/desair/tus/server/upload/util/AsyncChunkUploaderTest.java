package me.desair.tus.server.upload.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests verifying bounded 3-slot asynchronous chunk uploading, backpressure, exception
 * translation, timeout aborts, and CallerRunsPolicy degradation in {@link AsyncChunkUploader}.
 */
public class AsyncChunkUploaderTest {

  private ExecutorService executor;
  private File tempDir;

  @Before
  public void setUp() throws Exception {
    executor = Executors.newFixedThreadPool(4);
    tempDir = Files.createTempDirectory("tus-async-uploader-test").toFile();
  }

  @After
  public void tearDown() throws Exception {
    if (executor != null) {
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
    }
    if (tempDir != null && tempDir.exists()) {
      org.apache.commons.io.FileUtils.deleteQuietly(tempDir);
    }
  }

  @Test
  public void testFirstChunkSubmissionDirectToSlot3() throws Exception {
    File chunk1 = new File(tempDir, "chunk1.tmp");
    Files.write(chunk1.toPath(), new byte[] {1, 2, 3});

    CountDownLatch uploadFinished = new CountDownLatch(1);
    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          chunk1,
          3,
          "part-1",
          () -> {
            uploadFinished.countDown();
          });

      assertTrue(
          "Upload task should execute in background", uploadFinished.await(3, TimeUnit.SECONDS));
      int confirmed = uploader.drainAndComplete(3000);
      assertEquals("One chunk confirmed uploaded", 1, confirmed);
    }

    // Verify temp file is deleted by the background worker in finally block
    assertFalse("Chunk file should be cleaned up after successful upload", chunk1.exists());
  }

  @Test
  public void testPipelinedSubmissionThroughSlot2AndSlot3() throws Exception {
    File chunk1 = new File(tempDir, "chunk1.tmp");
    File chunk2 = new File(tempDir, "chunk2.tmp");
    File chunk3 = new File(tempDir, "chunk3.tmp");
    Files.write(chunk1.toPath(), new byte[] {1});
    Files.write(chunk2.toPath(), new byte[] {2});
    Files.write(chunk3.toPath(), new byte[] {3});

    List<String> uploadedOrder = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch unblockSlot3 = new CountDownLatch(1);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      // Chunk 1 occupies Slot 3
      uploader.submitChunk(
          chunk1,
          1,
          "part-1",
          () -> {
            unblockSlot3.await(3, TimeUnit.SECONDS);
            uploadedOrder.add("part-1");
          });

      // Chunk 2 placed in Slot 2 (waiting) while Chunk 1 is running
      uploader.submitChunk(
          chunk2,
          1,
          "part-2",
          () -> {
            uploadedOrder.add("part-2");
          });

      // Unblock Chunk 1 so it finishes
      unblockSlot3.countDown();

      // Chunk 3 submitted: blocks until Chunk 1 finishes, promotes Chunk 2 to Slot 3, stores Chunk
      // 3 in Slot 2
      uploader.submitChunk(
          chunk3,
          1,
          "part-3",
          () -> {
            uploadedOrder.add("part-3");
          });

      int confirmed = uploader.drainAndComplete(3000);
      assertEquals("All 3 chunks confirmed uploaded", 3, confirmed);
      assertEquals(
          "Parts uploaded in sequential order",
          List.of("part-1", "part-2", "part-3"),
          uploadedOrder);
    }

    assertFalse("Chunk 1 should be deleted", chunk1.exists());
    assertFalse("Chunk 2 should be deleted", chunk2.exists());
    assertFalse("Chunk 3 should be deleted", chunk3.exists());
  }

  @Test
  public void testBackpressureBlocksReaderWhenBothSlotsFull() throws Exception {
    File chunk1 = new File(tempDir, "chunk1.tmp");
    File chunk2 = new File(tempDir, "chunk2.tmp");
    File chunk3 = new File(tempDir, "chunk3.tmp");
    Files.write(chunk1.toPath(), new byte[] {1});
    Files.write(chunk2.toPath(), new byte[] {2});
    Files.write(chunk3.toPath(), new byte[] {3});

    CountDownLatch chunk1Hold = new CountDownLatch(1);
    AtomicBoolean chunk3Submitted = new AtomicBoolean(false);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          chunk1,
          1,
          "part-1",
          () -> {
            chunk1Hold.await(3, TimeUnit.SECONDS);
          });

      uploader.submitChunk(chunk2, 1, "part-2", () -> {});

      // Launch thread trying to submit chunk 3: must block because Slot 3 is busy and Slot 2 is
      // occupied
      Thread submitThread =
          new Thread(
              () -> {
                try {
                  uploader.submitChunk(chunk3, 1, "part-3", () -> {});
                  chunk3Submitted.set(true);
                } catch (IOException ignored) {
                }
              });
      submitThread.start();

      // Give thread 150ms to attempt submit; it must be blocked waiting on Slot 3
      Thread.sleep(150);
      assertFalse("Submit of chunk 3 must block due to backpressure", chunk3Submitted.get());

      // Release chunk 1
      chunk1Hold.countDown();
      submitThread.join(3000);

      assertTrue("Submit of chunk 3 unblocked after chunk 1 finished", chunk3Submitted.get());
      int confirmed = uploader.drainAndComplete(3000);
      assertEquals("All 3 chunks confirmed", 3, confirmed);
    }
  }

  @Test
  public void testUploadActionFailurePropagatesIOExceptionAndAborts() throws Exception {
    File chunk1 = new File(tempDir, "chunk1.tmp");
    File chunk2 = new File(tempDir, "chunk2.tmp");
    Files.write(chunk1.toPath(), new byte[] {1});
    Files.write(chunk2.toPath(), new byte[] {2});

    CountDownLatch chunk1Started = new CountDownLatch(1);
    CountDownLatch allowChunk1ToFail = new CountDownLatch(1);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          chunk1,
          1,
          "part-1",
          () -> {
            chunk1Started.countDown();
            try {
              allowChunk1ToFail.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
            throw new IOException("Simulated S3 network failure");
          });

      // Wait until chunk 1 is actively running in background slot 3
      assertTrue(chunk1Started.await(5, TimeUnit.SECONDS));

      // With slot 3 actively running, submitChunk queues chunk 2 into slot 2 without throwing
      uploader.submitChunk(chunk2, 1, "part-2", () -> {});

      // Allow chunk 1 to fail
      allowChunk1ToFail.countDown();

      // Draining the pipeline detects the failure of chunk 1 and throws IOException
      try {
        uploader.drainAndComplete(3000);
        fail("Expected IOException from failed chunk upload");
      } catch (IOException e) {
        assertTrue(
            "Exception message should reflect upload failure",
            e.getMessage().contains("Simulated S3 network failure"));
      }
    }

    assertFalse("Chunk 2 should be cleaned up by abort()", chunk2.exists());
  }

  @Test
  public void testDrainTimeoutAbortsInFlightUpload() throws Exception {
    File chunk1 = new File(tempDir, "chunk1.tmp");
    Files.write(chunk1.toPath(), new byte[] {1});

    CountDownLatch hangLatch = new CountDownLatch(1);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          chunk1,
          1,
          "part-1",
          () -> {
            hangLatch.await(10, TimeUnit.SECONDS);
          });

      try {
        // Enforce short 200ms drain timeout
        uploader.drainAndComplete(200);
        fail("Expected timeout IOException during drain");
      } catch (IOException e) {
        assertTrue("Should report timeout error", e.getMessage().contains("Timed out after"));
        assertTrue("Should report key", e.getMessage().contains("part-1"));
      }
    } finally {
      hangLatch.countDown();
    }
  }

  @Test
  public void testCloseAutomaticallyAbortsIfUncompleted() throws Exception {
    File chunk1 = new File(tempDir, "chunk1.tmp");
    File chunk2 = new File(tempDir, "chunk2.tmp");
    Files.write(chunk1.toPath(), new byte[] {1});
    Files.write(chunk2.toPath(), new byte[] {2});

    CountDownLatch latch = new CountDownLatch(1);
    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          chunk1,
          1,
          "part-1",
          () -> {
            latch.await(3, TimeUnit.SECONDS);
          });
      uploader.submitChunk(chunk2, 1, "part-2", () -> {});
      // Exiting without calling drainAndComplete() triggers close() which calls abort()
    } finally {
      latch.countDown();
    }

    assertFalse(
        "Waiting chunk file in Slot 2 should be deleted by abort on close", chunk2.exists());
  }

  @Test
  public void testSynchronousQueueCallerRunsPolicyDegradation() throws Exception {
    // ThreadPool with exactly 1 thread and SynchronousQueue
    ThreadPoolExecutor singlePool =
        new ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    File chunk1 = new File(tempDir, "chunk1.tmp");
    File chunk2 = new File(tempDir, "chunk2.tmp");
    Files.write(chunk1.toPath(), new byte[] {1});
    Files.write(chunk2.toPath(), new byte[] {2});

    CountDownLatch poolThreadBusy = new CountDownLatch(1);
    CountDownLatch releaseBusy = new CountDownLatch(1);

    // Occupy the only thread in singlePool
    singlePool.submit(
        () -> {
          poolThreadBusy.countDown();
          try {
            releaseBusy.await(3, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
        });

    assertTrue("Single pool thread is now occupied", poolThreadBusy.await(3, TimeUnit.SECONDS));

    String mainThreadName = Thread.currentThread().getName();
    List<String> executionThreads = Collections.synchronizedList(new ArrayList<>());

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(singlePool)) {
      // Since pool is saturated and queue is SynchronousQueue, CallerRunsPolicy executes task
      // directly on caller thread
      uploader.submitChunk(
          chunk1,
          1,
          "part-1",
          () -> {
            executionThreads.add(Thread.currentThread().getName());
          });

      int confirmed = uploader.drainAndComplete(3000);
      assertEquals("One chunk confirmed", 1, confirmed);
      assertEquals(
          "Executed directly on caller thread without queue stall",
          mainThreadName,
          executionThreads.get(0));
    } finally {
      releaseBusy.countDown();
      singlePool.shutdownNow();
    }
  }

  @Test
  public void testHighConcurrencyMultiUploaderSimulation() throws Exception {
    int concurrentUploaders = 15;
    ExecutorService sharedPool =
        new ThreadPoolExecutor(
            4,
            4,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    CountDownLatch allDone = new CountDownLatch(concurrentUploaders);
    AtomicInteger totalUploadedChunks = new AtomicInteger(0);

    for (int i = 0; i < concurrentUploaders; i++) {
      final int uploaderId = i;
      new Thread(
              () -> {
                try {
                  File f1 = new File(tempDir, "c-" + uploaderId + "-1.tmp");
                  File f2 = new File(tempDir, "c-" + uploaderId + "-2.tmp");
                  Files.write(f1.toPath(), new byte[] {1, 2});
                  Files.write(f2.toPath(), new byte[] {3, 4});

                  try (AsyncChunkUploader uploader = new AsyncChunkUploader(sharedPool)) {
                    uploader.submitChunk(
                        f1,
                        2,
                        "u" + uploaderId + "-p1",
                        () -> {
                          Thread.sleep(20);
                        });
                    uploader.submitChunk(
                        f2,
                        2,
                        "u" + uploaderId + "-p2",
                        () -> {
                          Thread.sleep(20);
                        });
                    int confirmed = uploader.drainAndComplete(5000);
                    totalUploadedChunks.addAndGet(confirmed);
                  }
                } catch (Exception e) {
                  e.printStackTrace();
                } finally {
                  allDone.countDown();
                }
              })
          .start();
    }

    assertTrue(
        "All concurrent uploaders must complete within 10s", allDone.await(10, TimeUnit.SECONDS));
    assertEquals(
        "All 30 chunks (15 uploaders x 2 chunks) must complete", 30, totalUploadedChunks.get());

    sharedPool.shutdownNow();
  }

  @Test
  public void testGetConfirmedCount() throws Exception {
    File f1 = new File(tempDir, "count1.tmp");
    Files.write(f1.toPath(), new byte[] {1});

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      assertEquals(0, uploader.getConfirmedCount());
      uploader.submitChunk(f1, 1, "k1", () -> {});
      uploader.drainAndComplete(3000);
      assertEquals(1, uploader.getConfirmedCount());
    }
  }

  @Test
  public void testSubmitChunkDiscoversPreviousDoneUploadFailureAndCleansUp() throws Exception {
    File f1 = new File(tempDir, "err1.tmp");
    File f2 = new File(tempDir, "err2.tmp");
    Files.write(f1.toPath(), new byte[] {1});
    Files.write(f2.toPath(), new byte[] {2});

    CountDownLatch chunk1Done = new CountDownLatch(1);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          f1,
          1,
          "k1",
          () -> {
            chunk1Done.countDown();
            throw new IOException("Simulated disk read error on cloud worker");
          });

      assertTrue(chunk1Done.await(3, TimeUnit.SECONDS));
      // Give worker thread a brief moment to update Future state to done
      Thread.sleep(50);

      try {
        uploader.submitChunk(f2, 1, "k2", () -> {});
        fail("Should have thrown IOException when discovering chunk 1 failure");
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("Simulated disk read error on cloud worker"));
      }
    }

    assertFalse("Chunk 2 must be cleaned up on failure", f2.exists());
  }

  @Test
  public void testUploadActionThrowsRuntimeExceptionTranslatedToIOException() throws Exception {
    File f1 = new File(tempDir, "runtime_err.tmp");
    Files.write(f1.toPath(), new byte[] {1});

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          f1,
          1,
          "runtime-key",
          () -> {
            throw new IllegalStateException("Boom!");
          });

      try {
        uploader.drainAndComplete(3000);
        fail("Should have thrown IOException wrapping RuntimeException");
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("Upload failed for key runtime-key: Boom!"));
        assertTrue(e.getCause() instanceof IllegalStateException);
      }
    }
  }

  @Test
  public void testUploadActionThrowsErrorTranslatedToIOException() throws Exception {
    File f1 = new File(tempDir, "error_key.tmp");
    Files.write(f1.toPath(), new byte[] {1});

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          f1,
          1,
          "error-key",
          () -> {
            throw new AssertionError("Simulated fatal assertion error");
          });

      try {
        uploader.drainAndComplete(3000);
        fail("Should have thrown IOException wrapping AssertionError");
      } catch (IOException e) {
        assertTrue(
            e.getMessage().contains("Unexpected error during chunk upload for key error-key"));
      }
    }
  }

  @Test
  public void testDrainInterruptedThrowsIOException() throws Exception {
    File f1 = new File(tempDir, "interrupted.tmp");
    Files.write(f1.toPath(), new byte[] {1});

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch unblock = new CountDownLatch(1);

    AtomicBoolean gotInterruptedIOException = new AtomicBoolean(false);

    Thread drainThread =
        new Thread(
            () -> {
              try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
                uploader.submitChunk(
                    f1,
                    1,
                    "k-interrupt",
                    () -> {
                      started.countDown();
                      unblock.await(5, TimeUnit.SECONDS);
                    });
                uploader.drainAndComplete(5000);
              } catch (IOException e) {
                if (e.getMessage().contains("Interrupted waiting for chunk upload")) {
                  gotInterruptedIOException.set(true);
                }
              } catch (Exception ignored) {
              }
            });

    drainThread.start();
    assertTrue(started.await(3, TimeUnit.SECONDS));
    drainThread.interrupt();
    drainThread.join(3000);
    unblock.countDown();

    assertTrue(
        "Interrupted thread should throw IOException mentioning Interrupted",
        gotInterruptedIOException.get());
  }

  @Test
  public void testSubmitChunkBackpressureInterruptedThrowsIOException() throws Exception {
    File f1 = new File(tempDir, "bp-int1.tmp");
    File f2 = new File(tempDir, "bp-int2.tmp");
    File f3 = new File(tempDir, "bp-int3.tmp");
    Files.write(f1.toPath(), new byte[] {1});
    Files.write(f2.toPath(), new byte[] {2});
    Files.write(f3.toPath(), new byte[] {3});

    CountDownLatch f1Started = new CountDownLatch(1);
    CountDownLatch unblock = new CountDownLatch(1);
    AtomicBoolean gotBackpressureInterruptedException = new AtomicBoolean(false);

    Thread submitThread =
        new Thread(
            () -> {
              try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
                uploader.submitChunk(
                    f1,
                    1,
                    "k1",
                    () -> {
                      f1Started.countDown();
                      unblock.await(5, TimeUnit.SECONDS);
                    });
                uploader.submitChunk(f2, 1, "k2", () -> {});
                // Third chunk triggers waitForInFlight() backpressure
                uploader.submitChunk(f3, 1, "k3", () -> {});
              } catch (IOException e) {
                if (e.getMessage().contains("Interrupted")
                    && e.getMessage().contains("in-flight chunk upload")) {
                  gotBackpressureInterruptedException.set(true);
                }
              } catch (Exception ignored) {
              }
            });

    submitThread.start();
    assertTrue(f1Started.await(3, TimeUnit.SECONDS));
    // Brief sleep to let submitThread block in waitForInFlight()
    try {
      Thread.sleep(50);
    } catch (InterruptedException ignored) {
    }
    submitThread.interrupt();
    submitThread.join(3000);
    unblock.countDown();

    assertTrue(
        "Interrupted backpressure wait should throw IOException",
        gotBackpressureInterruptedException.get());
  }

  @Test
  public void testDrainAndCompleteDefaultTimeout() throws Exception {
    File chunk = new File(tempDir, "chunk-default-drain.tmp");
    Files.write(chunk.toPath(), "test-data".getBytes());

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(chunk, 9, "part-default", () -> {});
      int confirmed = uploader.drainAndComplete();
      assertEquals("One chunk confirmed uploaded using default timeout", 1, confirmed);
    }

    assertFalse("Chunk file should be cleaned up", chunk.exists());
  }

  @Test
  public void testCustomDrainTimeoutConstructorAndGetter() {
    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor, 12_345L)) {
      assertEquals(12_345L, uploader.getDrainTimeoutMs());
    }
  }

  @Test
  public void testDefaultDrainTimeoutConstant() {
    assertEquals(55_000L, AsyncChunkUploader.DEFAULT_DRAIN_TIMEOUT_MS);
    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      assertEquals(55_000L, uploader.getDrainTimeoutMs());
    }
  }

  @Test
  public void testSubmitChunkBackpressureContentionSignalThrowsIOException() throws Exception {
    File f1 = new File(tempDir, "contention-c1.tmp");
    File f2 = new File(tempDir, "contention-c2.tmp");
    File f3 = new File(tempDir, "contention-c3.tmp");
    Files.write(f1.toPath(), "data-1".getBytes());
    Files.write(f2.toPath(), "data-2".getBytes());
    Files.write(f3.toPath(), "data-3".getBytes());

    CountDownLatch f1Started = new CountDownLatch(1);
    CountDownLatch unblock = new CountDownLatch(1);
    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());
    InterruptibleInputStream interruptibleStream = new InterruptibleInputStream(bais);

    try (AsyncChunkUploader uploader =
        new AsyncChunkUploader(executor, 5000L, interruptibleStream)) {
      uploader.submitChunk(
          f1,
          6,
          "k1",
          () -> {
            f1Started.countDown();
            try {
              unblock.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
          });
      uploader.submitChunk(f2, 6, "k2", () -> {});
      assertTrue(f1Started.await(3, TimeUnit.SECONDS));

      // Signal contention before 3rd submitChunk which requires waiting
      interruptibleStream.interrupt();

      try {
        uploader.submitChunk(f3, 6, "k3", () -> {});
        fail("Expected IOException due to lock contention");
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("contention"));
      }
    } finally {
      unblock.countDown();
    }
  }

  @Test
  public void testAbortWithActiveInFlightUpload() throws Exception {
    File chunk1 = new File(tempDir, "abort-active-c1.tmp");
    Files.write(chunk1.toPath(), "data-abort".getBytes());

    CountDownLatch uploadStarted = new CountDownLatch(1);
    CountDownLatch unblock = new CountDownLatch(1);

    AsyncChunkUploader uploader = new AsyncChunkUploader(executor);
    uploader.submitChunk(
        chunk1,
        10,
        "k-abort",
        () -> {
          uploadStarted.countDown();
          try {
            unblock.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
        });

    assertTrue(uploadStarted.await(3, TimeUnit.SECONDS));
    // Calling abort while worker is running cancels inFlightUpload and waits up to 500ms
    uploader.abort();
    unblock.countDown();
    uploader.close();
  }

  @Test
  public void testAbortWhenThreadInterruptedRestoresInterruptStatus() throws Exception {
    ExecutorService mockExecutor = mock(ExecutorService.class);
    @SuppressWarnings("unchecked")
    java.util.concurrent.Future<Void> future = mock(java.util.concurrent.Future.class);
    when(mockExecutor.submit(any(Callable.class))).thenReturn(future);
    when(future.isDone()).thenReturn(false);
    when(future.get(500, TimeUnit.MILLISECONDS))
        .thenThrow(new InterruptedException("Interrupted during abort wait"));

    File f = new File(tempDir, "abort-interrupted.tmp");
    Files.write(f.toPath(), new byte[] {1});

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(mockExecutor)) {
      uploader.submitChunk(f, 1, "k-abort-intr", () -> {});
      try {
        uploader.abort();
        assertTrue(
            "Interrupted status must be restored when InterruptedException caught in abort()",
            Thread.interrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  public void testWaitForInFlightExecutionExceptionPropagated() throws Exception {
    File f1 = new File(tempDir, "exec-err-1.tmp");
    File f2 = new File(tempDir, "exec-err-2.tmp");
    File f3 = new File(tempDir, "exec-err-3.tmp");
    Files.write(f1.toPath(), "err1".getBytes());
    Files.write(f2.toPath(), "err2".getBytes());
    Files.write(f3.toPath(), "err3".getBytes());

    CountDownLatch f1Started = new CountDownLatch(1);
    CountDownLatch triggerFailure = new CountDownLatch(1);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          f1,
          4,
          "k-fail-1",
          () -> {
            f1Started.countDown();
            try {
              triggerFailure.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            throw new RuntimeException("S3 simulated upload failure");
          });

      assertTrue(f1Started.await(3, TimeUnit.SECONDS));
      // Submit slot 2 while slot 1 is blocked in latch
      uploader.submitChunk(f2, 4, "k-fail-2", () -> {});

      // In a background thread release triggerFailure after 100ms so failure happens while
      // waitForInFlight polls
      Executors.newSingleThreadScheduledExecutor()
          .schedule(triggerFailure::countDown, 100, TimeUnit.MILLISECONDS);

      // Submitting 3rd chunk triggers waitForInFlight() for slot 1 which throws ExecutionException
      try {
        uploader.submitChunk(f3, 4, "k-fail-3", () -> {});
        fail("Expected IOException when in-flight upload threw ExecutionException");
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("S3 simulated upload failure"));
      }
    }
  }

  @Test
  public void testAbortWithActiveInFlightUploadWaitsAndCancels() throws Exception {
    File f1 = new File(tempDir, "abort-f1.tmp");
    Files.write(f1.toPath(), new byte[] {1, 2, 3});

    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch hangLatch = new CountDownLatch(1);

    AsyncChunkUploader uploader = new AsyncChunkUploader(executor);
    uploader.submitChunk(
        f1,
        3,
        "k-abort",
        () -> {
          taskStarted.countDown();
          try {
            hangLatch.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
        });

    assertTrue(taskStarted.await(3, TimeUnit.SECONDS));
    uploader.abort();

    hangLatch.countDown();
    // Allow up to 3 seconds for asynchronous background worker thread to execute its finally
    // cleanup
    boolean deleted = false;
    for (int i = 0; i < 60; i++) {
      if (!f1.exists()) {
        deleted = true;
        break;
      }
      Thread.sleep(50);
    }
    assertTrue("File should be cleaned up by worker thread finally block", deleted);
  }

  @Test
  public void testWaitForInFlightUploadTimeoutPolling() throws Exception {
    File f1 = new File(tempDir, "poll-f1.tmp");
    File f2 = new File(tempDir, "poll-f2.tmp");
    File f3 = new File(tempDir, "poll-f3.tmp");
    Files.write(f1.toPath(), new byte[] {1});
    Files.write(f2.toPath(), new byte[] {2});
    Files.write(f3.toPath(), new byte[] {3});

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          f1,
          1,
          "k-poll-1",
          () -> {
            try {
              Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
          });

      uploader.submitChunk(f2, 1, "k-poll-2", () -> {});

      // Submitting 3rd chunk triggers waitForInFlightUpload(), which will encounter
      // TimeoutException
      // on the 50ms tick before succeeding on the 3rd tick.
      uploader.submitChunk(f3, 1, "k-poll-3", () -> {});
      uploader.drainAndComplete(3000);
    }
  }

  @Test
  public void testSubmitChunkThrowsDeletesTempFileImmediately() throws Exception {
    ExecutorService mockExecutor = mock(ExecutorService.class);
    @SuppressWarnings("unchecked")
    java.util.concurrent.Future<Void> future = mock(java.util.concurrent.Future.class);
    when(mockExecutor.submit(any(Callable.class)))
        .thenThrow(new RejectedExecutionException("Executor saturated"));

    File f = new File(tempDir, "reject-f.tmp");
    Files.write(f.toPath(), new byte[] {1, 2, 3});
    assertTrue(f.exists());

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(mockExecutor)) {
      uploader.submitChunk(f, 3, "k-rej", () -> {});
      fail("Expected RejectedExecutionException");
    } catch (RejectedExecutionException e) {
      // Temp file must be immediately deleted upon submit failure
      assertFalse(f.exists());
    }
  }

  @Test
  public void testCheckAndConfirmInFlightInterruptedThrowsIOException() throws Exception {
    File f1 = new File(tempDir, "interrupt-f1.tmp");
    Files.write(f1.toPath(), new byte[] {1});

    CountDownLatch uploadStarted = new CountDownLatch(1);
    CountDownLatch holdUpload = new CountDownLatch(1);

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(executor)) {
      uploader.submitChunk(
          f1,
          1,
          "k-int-1",
          () -> {
            uploadStarted.countDown();
            try {
              holdUpload.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
          });

      assertTrue(uploadStarted.await(3, TimeUnit.SECONDS));

      Thread.currentThread().interrupt();
      try {
        uploader.checkAndConfirmInFlight();
        fail("Expected IOException");
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("Interrupted while checking in-flight chunk upload"));
      } finally {
        Thread.interrupted(); // clear
        holdUpload.countDown();
      }
    }
  }
}
