package me.desair.tus.server.upload.util;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates asynchronous, pipelined uploading of chunk files to cloud storage backends (S3,
 * Azure).
 *
 * <p>Uses a bounded 3-slot pipeline (triple-buffering):
 *
 * <ul>
 *   <li><b>Slot 1 (Receiving):</b> The caller/servlet thread reads incoming request bytes into a
 *       local temp chunk file.
 *   <li><b>Slot 2 (Waiting):</b> At most one completed chunk file held on disk waiting for the
 *       active cloud upload to complete.
 *   <li><b>Slot 3 (Uploading):</b> A discrete task running on the shared thread pool actively
 *       streaming a chunk to the cloud.
 * </ul>
 *
 * <p>This pipeline overlaps network reception from the client with upstream cloud staging,
 * eliminating client TCP stall periods while maintaining strict upper bounds on memory, disk space,
 * and thread usage.
 */
public class AsyncChunkUploader implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AsyncChunkUploader.class);

  public static final long DEFAULT_DRAIN_TIMEOUT_MS = 55_000L;

  @FunctionalInterface
  public interface ChunkUploadAction {
    /**
     * Executes the cloud upload action for a single chunk.
     *
     * @throws Exception If the upload to the cloud provider fails
     */
    void upload() throws Exception;
  }

  private final ExecutorService executor;
  private final long drainTimeoutMs;

  // Slot 3: In-flight upload task and its associated local file and key
  private Future<?> inFlightUpload;
  private File inFlightFile;
  private String inFlightKey;

  // Slot 2: Waiting chunk awaiting execution
  private File waitingChunkFile;
  private long waitingChunkSize;
  private String waitingKey;
  private ChunkUploadAction waitingAction;

  // Number of chunks confirmed uploaded to the cloud
  private int confirmedCount;

  // Set to true once drainAndComplete() has successfully finished
  private boolean completed;

  /**
   * Constructs an uploader using the given shared executor and default 55-second drain timeout.
   *
   * @param executor Shared thread pool executor for background chunk uploads
   */
  public AsyncChunkUploader(ExecutorService executor) {
    this(executor, DEFAULT_DRAIN_TIMEOUT_MS);
  }

  /**
   * Constructs an uploader using the given shared executor and explicit drain timeout.
   *
   * @param executor Shared thread pool executor for background chunk uploads
   * @param drainTimeoutMs Maximum duration in milliseconds to drain in-flight chunks
   */
  public AsyncChunkUploader(ExecutorService executor, long drainTimeoutMs) {
    this.executor = Objects.requireNonNull(executor, "ExecutorService must not be null");
    this.drainTimeoutMs = drainTimeoutMs;
  }

  /**
   * Submits a newly completed local chunk file into the upload pipeline.
   *
   * <p>If Slot 3 is idle or completed, the chunk begins uploading immediately. If Slot 3 is busy
   * and Slot 2 is empty, the chunk is placed into Slot 2. If both Slot 3 and Slot 2 are occupied,
   * this call blocks until Slot 3 finishes (applying backpressure to the client stream).
   *
   * @param tempFile The local chunk file containing the chunk bytes
   * @param size The size of the chunk in bytes
   * @param preassignedKey The pre-assigned cloud object key or block ID
   * @param uploadAction The upload action to execute on the worker thread
   * @throws IOException If a previous chunk upload failed or thread was interrupted
   */
  public void submitChunk(
      File tempFile, long size, String preassignedKey, ChunkUploadAction uploadAction)
      throws IOException {
    Objects.requireNonNull(tempFile, "tempFile must not be null");
    Objects.requireNonNull(uploadAction, "uploadAction must not be null");

    // Check if previous in-flight upload has finished, advancing confirmation count
    if (inFlightUpload != null && inFlightUpload.isDone()) {
      try {
        checkAndConfirmInFlight();
      } catch (IOException e) {
        FileUtils.deleteQuietly(tempFile);
        throw e;
      }
    }

    try {
      if (waitingChunkFile == null) {
        if (inFlightUpload == null) {
          // Branch 1: Slot 3 is free and Slot 2 is empty. Submit directly to Slot 3.
          submitToSlot3(tempFile, size, preassignedKey, uploadAction);
        } else {
          // Branch 2: Slot 3 is actively uploading, but Slot 2 (waiting) is free.
          // Store in Slot 2 and return immediately so the caller can read the next chunk.
          this.waitingChunkFile = tempFile;
          this.waitingChunkSize = size;
          this.waitingKey = preassignedKey;
          this.waitingAction = uploadAction;
        }
      } else {
        // Branch 3: Slot 2 already has a waiting chunk.
        // To preserve strict sequential chunk ordering, Slot 2's chunk must be uploaded before the
        // new chunk.
        // If Slot 3 is still active, apply backpressure by waiting for it to finish.
        if (inFlightUpload != null) {
          waitForInFlight();
        }

        // Slot 3 is now free. Promote Slot 2 into Slot 3.
        submitToSlot3(waitingChunkFile, waitingChunkSize, waitingKey, waitingAction);
        this.waitingChunkFile = null;
        this.waitingAction = null;

        // Place the newly arrived chunk into the now-empty Slot 2.
        this.waitingChunkFile = tempFile;
        this.waitingChunkSize = size;
        this.waitingKey = preassignedKey;
        this.waitingAction = uploadAction;
      }
    } catch (IOException | RuntimeException e) {
      FileUtils.deleteQuietly(tempFile);
      throw e;
    }
  }

  /**
   * Drains all remaining chunks in the pipeline (Slot 3 and Slot 2) using the configured drain
   * timeout (defaults to {@link #DEFAULT_DRAIN_TIMEOUT_MS}, 55 seconds).
   *
   * @return The total number of confirmed successfully uploaded chunks
   * @throws IOException If any chunk upload fails or times out
   */
  public int drainAndComplete() throws IOException {
    return drainAndComplete(drainTimeoutMs);
  }

  /**
   * Returns the configured drain timeout in milliseconds.
   *
   * @return Drain timeout in milliseconds
   */
  public long getDrainTimeoutMs() {
    return drainTimeoutMs;
  }

  /**
   * Drains all remaining chunks in the pipeline (Slot 3 and Slot 2) up to the specified timeout.
   *
   * <p>Used upon stream completion or client interruption to ensure all received bytes are
   * persisted to cloud storage before releasing the upload lock.
   *
   * @param timeoutMs Maximum time in milliseconds to wait for in-flight and waiting chunks
   * @return The total number of confirmed successfully uploaded chunks
   * @throws IOException If any chunk upload fails or times out
   */
  public int drainAndComplete(long timeoutMs) throws IOException {
    long deadline = System.currentTimeMillis() + timeoutMs;

    // 1. Await Slot 3 if active
    if (inFlightUpload != null) {
      long remaining = Math.max(1L, deadline - System.currentTimeMillis());
      awaitFuture(inFlightUpload, remaining, inFlightKey);
      confirmedCount++;
      inFlightUpload = null;
      inFlightFile = null;
      inFlightKey = null;
    }

    // 2. If Slot 2 has a waiting chunk, promote it to Slot 3 and await its completion
    if (waitingChunkFile != null) {
      submitToSlot3(waitingChunkFile, waitingChunkSize, waitingKey, waitingAction);
      waitingChunkFile = null;
      waitingAction = null;

      long remaining = Math.max(1L, deadline - System.currentTimeMillis());
      awaitFuture(inFlightUpload, remaining, inFlightKey);
      confirmedCount++;
      inFlightUpload = null;
      inFlightFile = null;
      inFlightKey = null;
    }

    completed = true;
    return confirmedCount;
  }

  /**
   * Aborts the pipeline, cancelling any active cloud upload and deleting any remaining temp files.
   */
  public void abort() {
    if (inFlightUpload != null && !inFlightUpload.isDone()) {
      inFlightUpload.cancel(true);
    }
    if (inFlightFile != null) {
      FileUtils.deleteQuietly(inFlightFile);
      inFlightFile = null;
    }
    if (waitingChunkFile != null) {
      FileUtils.deleteQuietly(waitingChunkFile);
      waitingChunkFile = null;
      waitingAction = null;
    }
  }

  /**
   * Returns the number of chunks confirmed successfully uploaded.
   *
   * @return Confirmed chunk count
   */
  public int getConfirmedCount() {
    return confirmedCount;
  }

  @Override
  public void close() {
    // If drainAndComplete() did not finish successfully, abort and clean up uncommitted files
    if (!completed) {
      abort();
    }
  }

  private void submitToSlot3(
      File tempFile, long size, String preassignedKey, ChunkUploadAction uploadAction) {
    this.inFlightFile = tempFile;
    this.inFlightKey = preassignedKey;

    // Wrap the upload action so temp file cleanup is guaranteed on the worker thread
    this.inFlightUpload =
        executor.submit(
            () -> {
              try {
                uploadAction.upload();
              } finally {
                // The worker owns tempFile once submitted; delete it immediately upon upload
                // completion
                FileUtils.deleteQuietly(tempFile);
              }
              return null;
            });
  }

  private void checkAndConfirmInFlight() throws IOException {
    try {
      inFlightUpload.get();
      confirmedCount++;
      inFlightUpload = null;
      inFlightFile = null;
      inFlightKey = null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      abort();
      throw new IOException(
          "Interrupted while checking in-flight chunk upload for " + inFlightKey, e);
    } catch (ExecutionException e) {
      abort();
      throw translateExecutionException(e, inFlightKey);
    }
  }

  private void waitForInFlight() throws IOException {
    checkAndConfirmInFlight();
  }

  private void awaitFuture(Future<?> future, long timeoutMs, String key) throws IOException {
    try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      abort();
      throw new IOException("Interrupted waiting for chunk upload for key " + key, e);
    } catch (TimeoutException e) {
      abort();
      throw new IOException(
          "Timed out after " + timeoutMs + "ms waiting for chunk upload for key " + key, e);
    } catch (ExecutionException e) {
      abort();
      throw translateExecutionException(e, key);
    }
  }

  private IOException translateExecutionException(ExecutionException e, String key) {
    Throwable cause = e.getCause();
    if (cause instanceof IOException) {
      return (IOException) cause;
    } else if (cause instanceof RuntimeException) {
      return new IOException("Upload failed for key " + key + ": " + cause.getMessage(), cause);
    } else {
      return new IOException(
          "Unexpected error during chunk upload for key " + key, cause != null ? cause : e);
    }
  }
}
