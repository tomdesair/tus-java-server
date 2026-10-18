package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.exception.TusException;

/**
 * A no-op implementation of {@link UploadLockingService} following the Null Object Pattern.
 *
 * <p>All locking operations return a no-op {@link UploadLock} handle, and lifecycle/registration
 * methods execute as no-ops. Useful for unit tests or specialized embedding environments where
 * concurrency control is managed externally.
 */
public class NoOpUploadLockingService implements UploadLockingService {

  private static final UploadLock NO_OP_LOCK =
      new UploadLock() {
        @Override
        public String getUploadUri() {
          return null;
        }

        @Override
        public void release() {}

        @Override
        public void close() {}
      };

  @Override
  public UploadLock lockUploadByUri(String requestURI) throws TusException, IOException {
    return NO_OP_LOCK;
  }

  @Override
  public UploadLock lockUpload(UploadId id) throws TusException, IOException {
    return NO_OP_LOCK;
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    // No-op
  }

  @Override
  public boolean isLocked(UploadId id) {
    return false;
  }

  @Override
  public void cleanupLock(UploadId id) throws IOException {
    // No-op
  }

  @Override
  public void registerInputStream(String requestUri, InputStream inputStream) {
    // No-op
  }

  @Override
  public void requestLockRelease(String requestUri) {
    // No-op
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    // No-op
  }

  @Override
  public void close() throws IOException {
    // No-op
  }
}
