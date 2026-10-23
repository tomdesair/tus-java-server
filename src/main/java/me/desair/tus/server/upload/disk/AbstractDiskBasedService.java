package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.upload.AbstractCloseableResourceService;
import me.desair.tus.server.upload.UploadId;
import org.apache.commons.lang3.Validate;

/** Common abstract super class to implement service that use the disk file system */
public class AbstractDiskBasedService extends AbstractCloseableResourceService {

  private Path storagePath;

  public AbstractDiskBasedService(String path) {
    this(path, null);
  }

  public AbstractDiskBasedService(String path, String shutdownHookName) {
    super(shutdownHookName);
    Validate.notBlank(path, "The storage path cannot be blank");
    this.storagePath = Paths.get(path);
    init();
  }

  @Override
  protected void cleanupOnClose() throws IOException {
    // Default implementation does nothing for simple disk services
  }

  public Path getStoragePath() {
    return storagePath;
  }

  protected Path getPathInStorageDirectory(UploadId id) {
    if (id == null) {
      return null;
    }
    return DiskUtils.resolveSafePath(
        storagePath, id.toString(), "Upload ID is not valid and would result in a path traversal");
  }

  private synchronized void init() {
    DiskUtils.initStorageDirectory(storagePath);
  }
}
