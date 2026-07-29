package me.desair.tus.server.creationwithupload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;

public class CreationWithUploadPostRequestHandlerTest {

  private CreationWithUploadPostRequestHandler handler;

  @Before
  public void setUp() {
    handler = new CreationWithUploadPostRequestHandler();
  }

  @Test
  public void testSupports() {
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PATCH), is(false));
  }

  @Test
  public void testProcessWithLockingService() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("5");
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/123");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(10L);
    uploadInfo.setOffset(0L);

    UploadInfo updatedInfo = new UploadInfo();
    updatedInfo.setLength(10L);
    updatedInfo.setOffset(5L);

    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class))).thenReturn(updatedInfo);

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    verify(lockingService).registerInputStream(eq("/files/123"), any());
    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "5");
  }

  @Test
  public void testProcessWithoutLockingService() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("5");
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/123");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(10L);
    uploadInfo.setOffset(0L);

    UploadInfo updatedInfo = new UploadInfo();
    updatedInfo.setLength(10L);
    updatedInfo.setOffset(5L);

    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class))).thenReturn(updatedInfo);

    handler.process(HttpMethod.POST, request, response, storageService, null, "owner", null);

    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "5");
  }
}
