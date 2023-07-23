package me.desair.tus.server.upload.disk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import org.junit.BeforeClass;
import org.junit.Test;

public class FileBasedLockTest {

  private static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "locks").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @Test
  public void testLockRelease() throws UploadAlreadyLockedException, IOException {
    UUID test = UUID.randomUUID();
    FileBasedLock lock =
        new FileBasedLock("/test/upload/" + test.toString(), storagePath.resolve(test.toString()));
    lock.close();
    assertFalse(Files.exists(storagePath.resolve(test.toString())));
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testOverlappingLock() throws Exception {
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());
    try (FileBasedLock lock1 = new FileBasedLock("/test/upload/" + test.toString(), path)) {
      FileBasedLock lock2 = new FileBasedLock("/test/upload/" + test.toString(), path);
      lock2.close();
    }
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testAlreadyLocked() throws Exception {
    UUID test1 = UUID.randomUUID();
    Path path1 = storagePath.resolve(test1.toString());
    try (FileBasedLock lock1 = new FileBasedLock("/test/upload/" + test1.toString(), path1)) {
      FileBasedLock lock2 =
          new FileBasedLock("/test/upload/" + test1.toString(), path1) {
            @Override
            protected FileChannel createFileChannel() throws IOException {
              FileChannel channel = createFileChannelMock();
              doReturn(null).when(channel).tryLock(anyLong(), anyLong(), anyBoolean());
              return channel;
            }
          };
      lock2.close();
    }
  }

  @Test
  public void testLockReleaseLockRelease() throws UploadAlreadyLockedException, IOException {
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());
    FileBasedLock lock = new FileBasedLock("/test/upload/" + test.toString(), path);
    lock.close();
    assertFalse(Files.exists(path));
    lock = new FileBasedLock("/test/upload/" + test.toString(), path);
    lock.close();
    assertFalse(Files.exists(path));
  }

  @Test(expected = IOException.class)
  public void testLockIoException() throws UploadAlreadyLockedException, IOException {
    // Create directory on place where lock file will be
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      fail();
    }

    FileBasedLock lock = new FileBasedLock("/test/upload/" + test.toString(), path);
    lock.close();
  }

  private FileChannel createFileChannelMock() throws IOException {
    return spy(FileChannel.class);
  }
}
