package me.desair.tus.server.upload.disk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.disk.DiskLockingService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class DiskLockingServiceTest {

    public static final String UPLOAD_URL = "/upload/test";
    private DiskLockingService lockingService;

    @Mock
    private UploadIdFactory idFactory;

    private static Path storagePath;

    @BeforeClass
    public static void setupDataFolder() throws IOException {
        storagePath = Paths.get("target", "tus", "data").toAbsolutePath();
        Files.createDirectories(storagePath);
    }

    @AfterClass
    public static void destroyDataFolder() throws IOException {
        FileUtils.deleteDirectory(storagePath.toFile());
    }

    @Before
    public void setUp() {
        reset(idFactory);
        when(idFactory.getUploadURI()).thenReturn(UPLOAD_URL);
        when(idFactory.createId()).thenReturn(UUID.randomUUID());
        when(idFactory.readUploadId(anyString())).then(new Answer<UUID>() {
            @Override
            public UUID answer(final InvocationOnMock invocation) throws Throwable {
                return UUID.fromString(StringUtils.substringAfter(invocation.getArguments()[0].toString(),
                        UPLOAD_URL + "/"));
            }
        });

        lockingService = new DiskLockingService(idFactory, storagePath.toString());
    }

    @Test
    public void lockUploadByUri() throws Exception {
        UploadLock uploadLock = lockingService.lockUploadByUri("/upload/test/000003f1-a850-49de-af03-997272d834c9");

        assertThat(uploadLock, not(nullValue()));

        uploadLock.release();
    }

    @Test
    public void lockUploadNotExists() throws Exception {
        reset(idFactory);
        when(idFactory.readUploadId(anyString())).thenReturn(null);

        UploadLock uploadLock = lockingService.lockUploadByUri("/upload/test/000003f1-a850-49de-af03-997272d834c9");

        assertThat(uploadLock, nullValue());
    }

}