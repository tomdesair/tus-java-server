package me.desair.tus.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.upload.disk.DiskLockingService;
import me.desair.tus.server.upload.disk.DiskStorageService;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * Disk-backed integration test suite explicitly verifying legacy {@link DiskLockingService} (POSIX
 * {@code FileLock}) compatibility. Extends {@link AbstractITTusFileUploadService}.
 */
public class ITDiskTusFileUploadService extends AbstractITTusFileUploadService {

  protected static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "legacy-disk-data").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Override
  protected TusFileUploadService createTusFileUploadService() {
    return createTusFileUploadService(UPLOAD_URI);
  }

  @Override
  protected TusFileUploadService createTusFileUploadService(String uploadUri) {
    return new TusFileUploadService()
        .withUploadUri(uploadUri)
        .withUploadStorageService(new DiskStorageService(storagePath.toAbsolutePath().toString()))
        .withUploadLockingService(new DiskLockingService(storagePath.toAbsolutePath().toString()))
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withDownloadFeature()
        .withChunkedTransferDecoding(true);
  }
}
