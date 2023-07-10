package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.UploadId;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common abstract super class to implement service that use the disk file system */
public class AbstractDiskBasedService {

  private static final Logger log = LoggerFactory.getLogger(TusFileUploadService.class);

  private Path storagePath;

  public AbstractDiskBasedService(String path) {
    Validate.notBlank(path, "The storage path cannot be blank");
    this.storagePath = Paths.get(path);
  }

  protected Path getStoragePath() {
    return storagePath;
  }

  protected Path getPathInStorageDirectory(UploadId id) {
    if (!Files.exists(storagePath)) {
      init();
    }

    if (id == null) {
      return null;
    } else {
      return storagePath.resolve(id.toString());
    }
  }

  private synchronized void init() {
    if (!Files.exists(storagePath)) {
      try {
        Files.createDirectories(storagePath);
      } catch (IOException e) {
        String message =
            "Unable to create the directory specified by the storage path " + storagePath;
        log.error(message, e);
        throw new StoragePathNotAvailableException(message, e);
      }
    }
  }
}
