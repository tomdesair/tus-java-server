package me.desair.tus.server.upload.disk;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.desair.tus.server.upload.UploadId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AbstractDiskBasedServiceTest {

  private Path storagePath;
  private AbstractDiskBasedService service;

  @Before
  public void setUp() throws IOException {
    storagePath = Files.createTempDirectory("tus-test-storage");
    service = new AbstractDiskBasedService(storagePath.toString()) {};
  }

  @After
  public void tearDown() throws IOException {
    org.apache.commons.io.FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Test
  public void getPathInStorageDirectoryValidId() {
    UploadId id = new UploadId("valid-id-123");
    Path resolved = service.getPathInStorageDirectory(id);
    assertThat(resolved, is(storagePath.resolve("valid-id-123").normalize()));
  }

  @Test
  public void getPathInStorageDirectoryPathTraversal() {
    try {
      UploadId id = new UploadId("../../../../../etc/passwd");
      service.getPathInStorageDirectory(id);
      fail("Expected IllegalArgumentException to be thrown for path traversal attempt");
    } catch (IllegalArgumentException e) {
      assertThat(
          e.getMessage(), is("The upload ID cannot point to a path outside the storage directory"));
    }
  }
}
