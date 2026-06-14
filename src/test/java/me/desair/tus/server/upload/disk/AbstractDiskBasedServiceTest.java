package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AbstractDiskBasedServiceTest {

  private Path tempFile;

  @Before
  public void setUp() throws IOException {
    tempFile = Files.createTempFile("tus-test", "file");
  }

  @After
  public void tearDown() throws IOException {
    Files.deleteIfExists(tempFile);
  }

  @Test(expected = StoragePathNotAvailableException.class)
  public void testInitThrowsStoragePathNotAvailableException() {
    Path invalidDir = tempFile.resolve("invalid-dir");

    AbstractDiskBasedService service = new AbstractDiskBasedService(invalidDir.toString()) {};
    service.getStoragePath();
  }
}
