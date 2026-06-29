package me.desair.tus.server.upload.disk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import me.desair.tus.server.upload.UploadId;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AbstractDiskBasedServiceTest {

  private Path storagePath;
  private AbstractDiskBasedService service;
  private Path tempFile;

  @Before
  public void setUp() throws IOException {
    storagePath = Paths.get("target", "tus", "abstract-test").toAbsolutePath();
    service = new AbstractDiskBasedService(storagePath.toString());
    tempFile = Files.createTempFile("tus-test", "file");
  }

  @After
  public void tearDown() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
    Files.deleteIfExists(tempFile);
  }

  @Test
  public void testValidUploadId() {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    Path path = service.getPathInStorageDirectory(id);
    assertThat(path.startsWith(storagePath), is(true));
  }

  @Test
  public void testPathTraversalUploadId() {
    UploadId id = new UploadId("../../..%2F/etc/passwd");
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          service.getPathInStorageDirectory(id);
        });
  }

  @Test
  public void testPathTraversalUploadIdEncoded() {
    UploadId id = new UploadId("..%2f..%2f..%2f..%2f..%2fetc%2fpasswd");
    // Since URLCodec encodes "/", it becomes `..%2f..%2f..%2f..%2f..%2fetc%2fpasswd` literally,
    // which Java interprets as a single directory name. This doesn't actually traverse directories.
    // We expect NO exception to be thrown because it evaluates to a safe path starting with the
    // storage root.
    Path path = service.getPathInStorageDirectory(id);
    assertThat(path.startsWith(storagePath), is(true));
  }

  @Test(expected = StoragePathNotAvailableException.class)
  public void testInitThrowsStoragePathNotAvailableException() {
    Path invalidDir = tempFile.resolve("invalid-dir");

    AbstractDiskBasedService invalidService =
        new AbstractDiskBasedService(invalidDir.toString()) {};
    invalidService.getStoragePath();
  }
}
