package me.desair.tus.server.upload.disk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import me.desair.tus.server.exception.UploadAlreadyLockedException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;


public class FileBasedLockTest {

    private DiskStorageService storageService;

    private static Path storagePath;

    @BeforeClass
    public static void setupDataFolder() throws IOException {
        storagePath = Paths.get("target", "tus", "locks").toAbsolutePath();
        Files.createDirectories(storagePath);
    }

    @Test
    public void testLockRelease() throws UploadAlreadyLockedException, IOException {
        UUID test = UUID.randomUUID();
        FileBasedLock lock = new FileBasedLock("/test/upload/" + test.toString(), storagePath.resolve(test.toString()));
        lock.release();
        assertFalse(Files.exists(storagePath.resolve(test.toString())));
    }

    @Test(expected = UploadAlreadyLockedException.class)
    public void testOverlappingLock() throws Exception {
        UUID test = UUID.randomUUID();
        Path path = storagePath.resolve(test.toString());
        try(FileBasedLock lock1 = new FileBasedLock("/test/upload/" + test.toString(), path)) {
            FileBasedLock lock2 = new FileBasedLock("/test/upload/" + test.toString(), path);
        }
    }

    @Test(expected = UploadAlreadyLockedException.class)
    public void testAlreadyLocked() throws Exception {
        UUID test1 = UUID.randomUUID();
        Path path1 = storagePath.resolve(test1.toString());
        try(FileBasedLock lock1 = new FileBasedLock("/test/upload/" + test1.toString(), path1)) {
            FileBasedLock lock2 = new FileBasedLock("/test/upload/" + test1.toString(), path1) {
                @Override
                protected FileChannel createFileChannel() throws IOException {
                    FileChannel channel = createFileChannelMock();
                    doReturn(null).when(channel).tryLock(anyLong(), anyLong(), anyBoolean());
                    return channel;
                }
            };
        }
    }

    @Test
    public void testLockReleaseLockRelease() throws UploadAlreadyLockedException, IOException {
        UUID test = UUID.randomUUID();
        Path path = storagePath.resolve(test.toString());
        FileBasedLock lock = new FileBasedLock("/test/upload/" + test.toString(), path);
        lock.release();
        assertFalse(Files.exists(path));
        lock = new FileBasedLock("/test/upload/" + test.toString(), path);
        lock.release();
        assertFalse(Files.exists(path));
    }

    @Test(expected = IOException.class)
    public void testLockIOException() throws UploadAlreadyLockedException, IOException {
        //Create directory on place where lock file will be
        UUID test = UUID.randomUUID();
        Path path = storagePath.resolve(test.toString());
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            fail();
        }

        FileBasedLock lock = new FileBasedLock("/test/upload/" + test.toString(), path);
    }

    private FileChannel createFileChannelMock() throws IOException {
        FileChannel channel = mock(FileChannel.class);

        Whitebox.setInternalState(channel, "closeLock", new Object());
        Whitebox.setInternalState(channel, "open", true);
        return channel;
    }
}