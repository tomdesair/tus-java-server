package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import me.desair.tus.server.upload.TimeBasedUploadIdFactory;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.cache.ThreadLocalCachedStorageAndLockingService;
import me.desair.tus.server.upload.concatenation.VirtualConcatenationService;
import me.desair.tus.server.upload.disk.DiskLockingService;
import me.desair.tus.server.upload.disk.DiskStorageService;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;

public class ITTusFileUploadServiceCached extends ITTusFileUploadService {

  @Override
  @Before
  public void setUp() {
    super.setUp();
    tusFileUploadService =
        tusFileUploadService
            .withThreadLocalCache(true)
            .withUploadIdFactory(new TimeBasedUploadIdFactory());
  }

  @Test
  public void testProcessUploadDoubleCached() throws Exception {
    String path = storagePath.toAbsolutePath().toString();
    UploadStorageService uploadStorageService = new DiskStorageService(path);
    UploadLockingService uploadLockingService = new DiskLockingService(path);

    ThreadLocalCachedStorageAndLockingService service2 =
        new ThreadLocalCachedStorageAndLockingService(uploadStorageService, uploadLockingService);

    service2.setUploadConcatenationService(new VirtualConcatenationService(service2));

    tusFileUploadService.withUploadStorageService(service2);
    tusFileUploadService.withUploadLockingService(service2);

    assertThat(service2.getUploadUri(), is(UPLOAD_URI));
    assertThat(uploadStorageService.getUploadUri(), is(UPLOAD_URI));

    testConcatenationCompleted();
  }

  @Test
  public void testCachedUploadDifferentKey() throws Exception {
    String uploadContent = "This is an upload of someone else";

    // Create upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_CREATED);

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);

    // Upload bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "" + uploadContent.getBytes().length);
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_EXPIRES);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Download the upload to check content
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "" + uploadContent.getBytes().length);
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertThat(servletResponse.getContentAsString(), is("This is an upload of someone else"));

    // Try to download the upload under a different key
    reset();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(location);

    tusFileUploadService.process(servletRequest, servletResponse, "ALTER-EGO");
    assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertResponseHeader(HttpHeader.CONTENT_LENGTH, "0");
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
  }
}
