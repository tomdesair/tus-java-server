package me.desair.tus.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import me.desair.tus.server.upload.s3.S3UploadLock;
import org.junit.Test;

public class S3UploadLockJsonSerializerTest {

  @Test
  public void testSerializeAndDeserializeUploadLock() throws Exception {
    long now = System.currentTimeMillis();
    long expiry = now + 30000L;
    S3UploadLock lock =
        new S3UploadLock(
            "holder-uuid-1234",
            "/files/test-upload-id",
            "test-bucket",
            "locks/test.lock",
            "locks/test.stop",
            30000L,
            expiry);

    // Test String serialization and deserialization
    String json = S3UploadLockJsonSerializer.serialize(lock);
    assertNotNull(json);
    assertTrue(json.contains("\"bucket\":\"test-bucket\""));
    assertTrue(json.contains("\"requestUri\":\"/files/test-upload-id\""));
    assertTrue(json.contains("\"leaseDurationMs\":30000"));

    S3UploadLock deserialized = S3UploadLockJsonSerializer.deserialize(json);
    assertNotNull(deserialized);
    assertEquals("holder-uuid-1234", deserialized.getHolderId());
    assertEquals("/files/test-upload-id", deserialized.getRequestUri());
    assertEquals("/files/test-upload-id", deserialized.getUploadUri());
    assertEquals("test-bucket", deserialized.getBucket());
    assertEquals("locks/test.lock", deserialized.getLockKey());
    assertEquals("locks/test.stop", deserialized.getStopKey());
    assertEquals(30000L, deserialized.getLeaseDurationMs());
    assertEquals(expiry, deserialized.getExpiresAt());
    assertTrue(deserialized.getAcquiredAt() > 0);

    // Test byte array serialization
    byte[] bytes = S3UploadLockJsonSerializer.serializeToBytes(lock);
    assertNotNull(bytes);

    // Test InputStream deserialization
    S3UploadLock fromStream =
        S3UploadLockJsonSerializer.deserialize(new ByteArrayInputStream(bytes));
    assertNotNull(fromStream);
    assertEquals("holder-uuid-1234", fromStream.getHolderId());
    assertEquals("test-bucket", fromStream.getBucket());

    // Test OutputStream serialization
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    S3UploadLockJsonSerializer.serializeToStream(lock, baos);
    S3UploadLock fromStream2 =
        S3UploadLockJsonSerializer.deserialize(new ByteArrayInputStream(baos.toByteArray()));
    assertNotNull(fromStream2);
    assertEquals("holder-uuid-1234", fromStream2.getHolderId());
    assertEquals("locks/test.lock", fromStream2.getLockKey());
  }

  @Test
  public void testForwardCompatibilityUnknownProperties() throws Exception {
    // Future-proofing: Extra unknown properties must be ignored without throwing an exception
    String futureJson =
        "{\"holderId\":\"future-holder\",\"requestUri\":\"/files/1\",\"bucket\":\"b\",\"lockKey\":\"l\",\"stopKey\":\"s\",\"leaseDurationMs\":30000,\"expiresAt\":1700000000000,\"newProperty\":\"someValue\",\"anotherField\":123}";
    S3UploadLock futureLock = S3UploadLockJsonSerializer.deserialize(futureJson);
    assertNotNull(futureLock);
    assertEquals("future-holder", futureLock.getHolderId());
    assertEquals("/files/1", futureLock.getRequestUri());
    assertEquals(1700000000000L, futureLock.getExpiresAt());
  }

  @Test
  public void testNullAndEmptyHandling() throws Exception {
    assertNull(S3UploadLockJsonSerializer.serialize(null));
    assertNull(S3UploadLockJsonSerializer.serializeToBytes(null));
    assertNull(S3UploadLockJsonSerializer.deserialize((String) null));
    assertNull(S3UploadLockJsonSerializer.deserialize((InputStream) null));
    assertNull(S3UploadLockJsonSerializer.deserialize(""));
    assertNull(S3UploadLockJsonSerializer.deserialize("   "));

    try {
      S3UploadLockJsonSerializer.deserialize("invalid-json");
    } catch (Exception expected) {
      // expected
    }
  }
}
