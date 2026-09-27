package me.desair.tus.server.upload.disk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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

  @Test(expected = UploadAlreadyLockedException.class)
  public void testOverlappingLockFileChannelCloseException() throws Exception {
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());
    FileBasedLock lock =
        new FileBasedLock("/test/upload/" + test.toString(), path) {
          @Override
          protected FileChannel createFileChannel() throws IOException {
            FileChannel mockChannel = createFileChannelMock();
            doReturn(null).when(mockChannel).tryLock(anyLong(), anyLong(), anyBoolean());
            org.mockito.Mockito.doThrow(new IOException("Close error")).when(mockChannel).close();
            return mockChannel;
          }
        };
  }

  @Test
  public void testReleaseIOException() throws Exception {
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());
    FileBasedLock lock =
        new FileBasedLock("/test/upload/" + test.toString(), path) {
          @Override
          protected FileChannel createFileChannel() throws IOException {
            FileChannel mockChannel = createFileChannelMock();
            doReturn(org.mockito.Mockito.mock(java.nio.channels.FileLock.class))
                .when(mockChannel)
                .tryLock(anyLong(), anyLong(), anyBoolean());
            org.mockito.Mockito.doThrow(new IOException("Close error")).when(mockChannel).close();
            return mockChannel;
          }
        };
    lock.release();
  }

  @Test
  public void testTryLockIOExceptionClosesChannel() throws Exception {
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());
    java.util.concurrent.atomic.AtomicBoolean channelClosed =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    FileChannel customChannel =
        new FileChannel() {
          @Override
          public int read(java.nio.ByteBuffer dst) {
            return 0;
          }

          @Override
          public long read(java.nio.ByteBuffer[] dsts, int offset, int length) {
            return 0;
          }

          @Override
          public int write(java.nio.ByteBuffer src) {
            return 0;
          }

          @Override
          public long write(java.nio.ByteBuffer[] srcs, int offset, int length) {
            return 0;
          }

          @Override
          public long position() {
            return 0;
          }

          @Override
          public FileChannel position(long newPosition) {
            return this;
          }

          @Override
          public long size() {
            return 0;
          }

          @Override
          public FileChannel truncate(long size) {
            return this;
          }

          @Override
          public void force(boolean metaData) {}

          @Override
          public long transferTo(
              long position, long count, java.nio.channels.WritableByteChannel target) {
            return 0;
          }

          @Override
          public long transferFrom(
              java.nio.channels.ReadableByteChannel src, long position, long count) {
            return 0;
          }

          @Override
          public int read(java.nio.ByteBuffer dst, long position) {
            return 0;
          }

          @Override
          public int write(java.nio.ByteBuffer src, long position) {
            return 0;
          }

          @Override
          public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) {
            return null;
          }

          @Override
          public java.nio.channels.FileLock lock(long position, long size, boolean shared) {
            return null;
          }

          @Override
          public java.nio.channels.FileLock tryLock(long position, long size, boolean shared)
              throws IOException {
            throw new IOException("Simulated lock failure");
          }

          @Override
          protected void implCloseChannel() throws IOException {
            channelClosed.set(true);
          }
        };

    try {
      new FileBasedLock("/test/upload/" + test.toString(), path) {
        @Override
        protected FileChannel createFileChannel() throws IOException {
          return customChannel;
        }
      };
      fail("Expected IOException to be thrown");
    } catch (IOException e) {
      // Expected
    }

    // Verify fileChannel was closed when lock acquisition failed with IOException to prevent file
    // descriptor leaks.
    org.junit.Assert.assertTrue(channelClosed.get());
  }

  @Test
  public void testFileChannelCloseExceptionIgnoredOnLockAcquisitionFailure() throws Exception {
    UUID test = UUID.randomUUID();
    Path path = storagePath.resolve(test.toString());

    FileChannel channel = createFileChannelMock();
    doThrow(new IOException("Simulated lock failure"))
        .when(channel)
        .tryLock(anyLong(), anyLong(), anyBoolean());
    doThrow(new IOException("Simulated close exception during error cleanup"))
        .when(channel)
        .close();

    try {
      new FileBasedLock("/test/upload/" + test, path) {
        @Override
        protected FileChannel createFileChannel() {
          return channel;
        }
      };
      fail("Expected IOException to be thrown");
    } catch (IOException e) {
      org.junit.Assert.assertTrue(e.getMessage().contains("Unable to create or open file"));
    }
  }

  private FileChannel createFileChannelMock() throws IOException {
    return spy(FileChannel.class);
  }
}
