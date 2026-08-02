package me.desair.tus.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * Disk-backed integration tests for the RUFH (Resumable Uploads for HTTP) protocol implementation.
 */
public class ITRufhProtocol extends AbstractITRufhProtocol {

  protected static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "rufh", "data").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Override
  protected TusFileUploadService createTusFileUploadService() {
    return new TusFileUploadService()
        .withUploadUri(UPLOAD_URI)
        .withStoragePath(storagePath.toAbsolutePath().toString())
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withDownloadFeature();
  }
}
