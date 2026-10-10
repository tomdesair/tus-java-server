package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.AbortMultipartUploadArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.ComposeObjectArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.CreateMultipartUploadResponse;
import io.minio.Http;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.credentials.Credentials;
import io.minio.credentials.Provider;
import io.minio.messages.InitiateMultipartUploadResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

/** Unit tests for {@link S3ServerSideComposeHelper}. */
public class S3ServerSideComposeHelperTest {

  @Test
  public void testExtractEtagFromXml() {
    // Standard XML response
    String xml =
        "<CopyPartResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<LastModified>2026-10-03T12:00:00.000Z</LastModified>"
            + "<ETag>\"1234567890abcdef\"</ETag>"
            + "</CopyPartResult>";
    assertEquals("\"1234567890abcdef\"", S3ServerSideComposeHelper.extractEtagFromXml(xml));

    // Entity escaped XML quotes
    String escapedXml =
        "<CopyPartResult><ETag>&quot;9876543210fedcba&quot;</ETag></CopyPartResult>";
    assertEquals("\"9876543210fedcba\"", S3ServerSideComposeHelper.extractEtagFromXml(escapedXml));

    // Whitespace inside tag
    String whitespaceXml = "<CopyPartResult><ETag>   \"xyz123\"   </ETag></CopyPartResult>";
    assertEquals("\"xyz123\"", S3ServerSideComposeHelper.extractEtagFromXml(whitespaceXml));

    // Null or missing tags
    assertNull(S3ServerSideComposeHelper.extractEtagFromXml(null));
    assertNull(S3ServerSideComposeHelper.extractEtagFromXml(""));
    assertNull(S3ServerSideComposeHelper.extractEtagFromXml("<CopyPartResult></CopyPartResult>"));
    assertNull(S3ServerSideComposeHelper.extractEtagFromXml("<ETag></ETag>"));
  }

  @Test
  public void testFallbackWhenNotAvailable() throws Exception {
    MinioClient mockMinioClient = mock(MinioClient.class);
    // Since mock MinioClient does not have initialized asyncClient/baseUrl/httpClient fields,
    // isAvailable() will return false, triggering the composeObject() fallback.
    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(mockMinioClient);
    assertFalse(helper.isAvailable());

    List<String> partKeys = Arrays.asList("upload/part-1", "upload/part-2");
    helper.compose("my-bucket", "upload/target", partKeys);

    verify(mockMinioClient).composeObject(any(ComposeObjectArgs.class));
  }

  @Test
  public void testNullMinioClient() {
    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(null);
    assertFalse(helper.isAvailable());
  }

  @Test
  public void testNativeComposeSuccess() throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    MinioAsyncClient asyncClient = mock(MinioAsyncClient.class);
    OkHttpClient httpClient = mock(OkHttpClient.class);
    Provider provider = mock(Provider.class);

    Credentials creds = new Credentials("testAccessKey", "testSecretKey", "testSessionToken", null);
    when(provider.fetch()).thenReturn(creds);

    Http.BaseUrl baseUrl = new Http.BaseUrl(HttpUrl.parse("https://s3.us-west-2.amazonaws.com"));

    // Prepare createMultipartUpload response
    InitiateMultipartUploadResult initResult = mock(InitiateMultipartUploadResult.class);
    when(initResult.uploadId()).thenReturn("mock-upload-id");
    CreateMultipartUploadResponse createResponse =
        new CreateMultipartUploadResponse(
            null, "my-bucket", "us-west-2", "upload/target", initResult);
    when(asyncClient.createMultipartUpload(any(CreateMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(createResponse));

    // Prepare completeMultipartUpload response
    ObjectWriteResponse completeResponse =
        new ObjectWriteResponse(
            null, "my-bucket", "us-west-2", "upload/target", "etag-final", "v1");
    when(asyncClient.completeMultipartUpload(any(CompleteMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(completeResponse));

    // Mock OkHttp execution for uploadPartCopy
    Call call = mock(Call.class);
    when(httpClient.newCall(any(Request.class))).thenReturn(call);

    String partXml = "<CopyPartResult><ETag>\"part-etag-1\"</ETag></CopyPartResult>";
    ResponseBody body = ResponseBody.create(partXml, MediaType.parse("application/xml"));
    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("https://s3.us-west-2.amazonaws.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build();
    when(call.execute()).thenReturn(response);

    // Assemble helper with reflection fields populated
    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(minioClient);
    setField(helper, "asyncClient", asyncClient);
    setField(helper, "baseUrl", baseUrl);
    setField(helper, "httpClient", httpClient);
    setField(helper, "provider", provider);

    assertTrue(helper.isAvailable());

    List<String> partKeys = Collections.singletonList("upload/part-1");
    helper.compose("my-bucket", "upload/target", partKeys);

    verify(asyncClient).createMultipartUpload(any(CreateMultipartUploadArgs.class));
    verify(httpClient).newCall(any(Request.class));
    verify(asyncClient).completeMultipartUpload(any(CompleteMultipartUploadArgs.class));
  }

  @Test
  public void testNativeComposeHttpErrorAborts() throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    MinioAsyncClient asyncClient = mock(MinioAsyncClient.class);
    OkHttpClient httpClient = mock(OkHttpClient.class);
    Provider provider = mock(Provider.class);

    Credentials creds = new Credentials("testAccessKey", "testSecretKey", null, null);
    when(provider.fetch()).thenReturn(creds);

    Http.BaseUrl baseUrl = new Http.BaseUrl(HttpUrl.parse("https://s3.amazonaws.com"));

    // Prepare createMultipartUpload response
    InitiateMultipartUploadResult initResult = mock(InitiateMultipartUploadResult.class);
    when(initResult.uploadId()).thenReturn("mock-upload-id");
    CreateMultipartUploadResponse createResponse =
        new CreateMultipartUploadResponse(null, "my-bucket", "", "upload/target", initResult);
    when(asyncClient.createMultipartUpload(any(CreateMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(createResponse));

    when(asyncClient.abortMultipartUpload(any(AbortMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Mock OkHttp returning HTTP 400
    Call call = mock(Call.class);
    when(httpClient.newCall(any(Request.class))).thenReturn(call);

    ResponseBody body =
        ResponseBody.create(
            "<Error><Message>Test Error</Message></Error>", MediaType.parse("application/xml"));
    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("https://s3.amazonaws.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body(body)
            .build();
    when(call.execute()).thenReturn(response);

    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(minioClient);
    setField(helper, "asyncClient", asyncClient);
    setField(helper, "baseUrl", baseUrl);
    setField(helper, "httpClient", httpClient);
    setField(helper, "provider", provider);

    try {
      helper.compose("my-bucket", "upload/target", Collections.singletonList("upload/part-1"));
      fail("Expected IOException to be thrown");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("HTTP 400"));
    }

    verify(asyncClient).abortMultipartUpload(any(AbortMultipartUploadArgs.class));
  }

  @Test
  public void testNativeComposeMissingEtagHeaderFallback() throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    MinioAsyncClient asyncClient = mock(MinioAsyncClient.class);
    OkHttpClient httpClient = mock(OkHttpClient.class);

    Http.BaseUrl baseUrl = new Http.BaseUrl(HttpUrl.parse("https://s3.amazonaws.com"));

    InitiateMultipartUploadResult initResult = mock(InitiateMultipartUploadResult.class);
    when(initResult.uploadId()).thenReturn("mock-upload-id");
    CreateMultipartUploadResponse createResponse =
        new CreateMultipartUploadResponse(null, "my-bucket", "", "upload/target", initResult);
    when(asyncClient.createMultipartUpload(any(CreateMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(createResponse));

    ObjectWriteResponse completeResponse =
        new ObjectWriteResponse(null, "my-bucket", "", "upload/target", "etag-final", "v1");
    when(asyncClient.completeMultipartUpload(any(CompleteMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(completeResponse));

    Call call = mock(Call.class);
    when(httpClient.newCall(any(Request.class))).thenReturn(call);

    // Response body has no XML ETag tag, but response header has ETag
    ResponseBody body = ResponseBody.create("", MediaType.parse("application/xml"));
    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("https://s3.amazonaws.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("ETag", "\"header-etag\"")
            .body(body)
            .build();
    when(call.execute()).thenReturn(response);

    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(minioClient);
    setField(helper, "asyncClient", asyncClient);
    setField(helper, "baseUrl", baseUrl);
    setField(helper, "httpClient", httpClient);

    helper.compose("my-bucket", "upload/target", Collections.singletonList("upload/part-1"));

    verify(asyncClient).completeMultipartUpload(any(CompleteMultipartUploadArgs.class));
  }

  @Test
  public void testExplicitConnectionParametersConstructor() {
    MinioClient realClient =
        MinioClient.builder()
            .endpoint("https://s3.amazonaws.com")
            .credentials("testKey", "testSecret")
            .region("us-east-1")
            .build();

    S3ServerSideComposeHelper helperWithoutClient =
        new S3ServerSideComposeHelper(
            "https://s3.amazonaws.com", "us-east-1", "testKey", "testSecret");
    assertTrue(helperWithoutClient.isAvailable());

    S3ServerSideComposeHelper helperWithNullRegion =
        new S3ServerSideComposeHelper("https://s3.amazonaws.com", null, "testKey", "testSecret");
    assertTrue(helperWithNullRegion.isAvailable());

    S3ServerSideComposeHelper minioOnlyHelper = new S3ServerSideComposeHelper(realClient);
    assertFalse(minioOnlyHelper.isAvailable());
  }

  @Test(expected = IOException.class)
  public void testNativeComposeMissingEtagThrowsException() throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    MinioAsyncClient asyncClient = mock(MinioAsyncClient.class);
    OkHttpClient httpClient = mock(OkHttpClient.class);

    Http.BaseUrl baseUrl = new Http.BaseUrl(HttpUrl.parse("https://s3.amazonaws.com"));

    InitiateMultipartUploadResult initResult = mock(InitiateMultipartUploadResult.class);
    when(initResult.uploadId()).thenReturn("mock-upload-id");
    CreateMultipartUploadResponse createResponse =
        new CreateMultipartUploadResponse(null, "my-bucket", "", "upload/target", initResult);
    when(asyncClient.createMultipartUpload(any(CreateMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(createResponse));

    Call call = mock(Call.class);
    when(httpClient.newCall(any(Request.class))).thenReturn(call);

    // Response body has no XML ETag tag, and response header also has no ETag
    ResponseBody body = ResponseBody.create("<Empty/>", MediaType.parse("application/xml"));
    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("https://s3.amazonaws.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build();
    when(call.execute()).thenReturn(response);

    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(minioClient);
    setField(helper, "asyncClient", asyncClient);
    setField(helper, "baseUrl", baseUrl);
    setField(helper, "httpClient", httpClient);

    helper.compose("my-bucket", "upload/target", Collections.singletonList("upload/part-1"));
  }

  @Test
  public void testNativeComposeAbortFailureHandledQuietly() throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    MinioAsyncClient asyncClient = mock(MinioAsyncClient.class);
    OkHttpClient httpClient = mock(OkHttpClient.class);

    Http.BaseUrl baseUrl = new Http.BaseUrl(HttpUrl.parse("https://s3.amazonaws.com"));

    InitiateMultipartUploadResult initResult = mock(InitiateMultipartUploadResult.class);
    when(initResult.uploadId()).thenReturn("mock-upload-id");
    CreateMultipartUploadResponse createResponse =
        new CreateMultipartUploadResponse(null, "my-bucket", "", "upload/target", initResult);
    when(asyncClient.createMultipartUpload(any(CreateMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(createResponse));

    // Abort itself throws an exception to test the log.warn catch block
    CompletableFuture<io.minio.AbortMultipartUploadResponse> failedAbort =
        new CompletableFuture<>();
    failedAbort.completeExceptionally(new RuntimeException("Abort S3 error"));
    when(asyncClient.abortMultipartUpload(any(AbortMultipartUploadArgs.class)))
        .thenReturn(failedAbort);

    Call call = mock(Call.class);
    when(httpClient.newCall(any(Request.class))).thenReturn(call);

    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("https://s3.amazonaws.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("Internal Server Error")
            .body(ResponseBody.create("error", MediaType.parse("text/plain")))
            .build();
    when(call.execute()).thenReturn(response);

    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(minioClient);
    setField(helper, "asyncClient", asyncClient);
    setField(helper, "baseUrl", baseUrl);
    setField(helper, "httpClient", httpClient);

    try {
      helper.compose("my-bucket", "upload/target", Collections.singletonList("upload/part-1"));
      fail("Expected IOException");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("HTTP 500"));
    }
  }

  @Test
  public void testExplicitConnectionParametersInitializationFailureHandledGracefully() {
    // When endpoint contains invalid syntax that triggers an exception during MinioAsyncClient
    // creation, the constructor catches the exception and logs a warning gracefully without
    // throwing.
    S3ServerSideComposeHelper helper =
        new S3ServerSideComposeHelper(
            "http://:::invalid:::", "us-east-1", "accessKey", "secretKey");
    assertFalse(helper.isAvailable());
  }

  @Test
  public void testNativeComposeRegionFallbackToLocal() throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    MinioAsyncClient asyncClient = mock(MinioAsyncClient.class);
    OkHttpClient httpClient = mock(OkHttpClient.class);
    Provider provider = mock(Provider.class);

    Credentials creds = new Credentials("testAccessKey", "testSecretKey", null, null);
    when(provider.fetch()).thenReturn(creds);

    // Endpoint without AWS region domain (e.g. localhost) so baseUrl.region() is empty
    Http.BaseUrl baseUrl = new Http.BaseUrl(HttpUrl.parse("http://localhost:9000"));

    InitiateMultipartUploadResult initResult = mock(InitiateMultipartUploadResult.class);
    when(initResult.uploadId()).thenReturn("mock-upload-id");
    CreateMultipartUploadResponse createResponse =
        new CreateMultipartUploadResponse(null, "my-bucket", "", "upload/target", initResult);
    when(asyncClient.createMultipartUpload(any(CreateMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(createResponse));

    ObjectWriteResponse completeResponse =
        new ObjectWriteResponse(null, "my-bucket", "", "upload/target", "etag-final", "v1");
    when(asyncClient.completeMultipartUpload(any(CompleteMultipartUploadArgs.class)))
        .thenReturn(CompletableFuture.completedFuture(completeResponse));

    Call call = mock(Call.class);
    when(httpClient.newCall(any(Request.class))).thenReturn(call);

    String partXml = "<CopyPartResult><ETag>\"part-etag-1\"</ETag></CopyPartResult>";
    ResponseBody body = ResponseBody.create(partXml, MediaType.parse("application/xml"));
    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("http://localhost:9000").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build();
    when(call.execute()).thenReturn(response);

    S3ServerSideComposeHelper helper = new S3ServerSideComposeHelper(minioClient);
    setField(helper, "asyncClient", asyncClient);
    setField(helper, "baseUrl", baseUrl);
    setField(helper, "httpClient", httpClient);
    setField(helper, "provider", provider);
    setField(helper, "explicitRegion", null);

    // Verify composition executes with fallback region "local" without errors
    helper.compose("my-bucket", "upload/target", Collections.singletonList("upload/part-1"));
    verify(asyncClient).completeMultipartUpload(any(CompleteMultipartUploadArgs.class));
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = S3ServerSideComposeHelper.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
