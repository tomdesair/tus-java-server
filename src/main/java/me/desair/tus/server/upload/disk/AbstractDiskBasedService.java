package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.AbstractCloseableResourceService;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common abstract super class to implement service that use the disk file system */
public class AbstractDiskBasedService extends AbstractCloseableResourceService {

  private static final Logger log = LoggerFactory.getLogger(TusFileUploadService.class);

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
    } else {
      Path path = storagePath.resolve(id.toString());
      if (!path.normalize().toAbsolutePath().startsWith(storagePath.normalize().toAbsolutePath())) {
        throw new IllegalArgumentException(
            "Upload ID is not valid and would result in a path traversal");
      }
      return path;
    }
  }

  private synchronized void init() {
    try {
      Utils.ensureDirectoryExists(storagePath);
    } catch (IOException e) {
      String message =
          "Unable to create the directory specified by the storage path " + storagePath;
      log.error(message, e);
      throw new StoragePathNotAvailableException(message, e);
    }
  }
}
