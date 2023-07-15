package me.desair.tus.server.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.UUID;
import org.junit.Test;

/** Test class for the UploadId class. */
public class UploadIdTest {

  @Test
  public void getOriginalObjectUuid() {
    UUID id = UUID.randomUUID();
    UploadId uploadId = new UploadId(id);
    assertEquals(id.toString(), uploadId.toString());
    assertEquals(id, uploadId.getOriginalObject());
  }

  @Test
  public void getOriginalObjectLong() {
    UploadId uploadId = new UploadId(1337L);
    assertEquals("1337", uploadId.toString());
    assertEquals(1337L, uploadId.getOriginalObject());
  }

  @Test(expected = NullPointerException.class)
  public void testNullConstructor() {
    new UploadId(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBlankConstructor() {
    new UploadId(" \t");
  }

  @Test
  public void toStringNotYetUrlSafe() {
    UploadId uploadId = new UploadId("my test id/1");
    assertEquals("my+test+id%2F1", uploadId.toString());
  }

  @Test
  public void toStringNotYetUrlSafe2() {
    UploadId uploadId = new UploadId("id+%2F1+/+1");
    assertEquals("id+%2F1+/+1", uploadId.toString());
  }

  @Test
  public void toStringAlreadyUrlSafe() {
    UploadId uploadId = new UploadId("my+test+id%2F1");
    assertEquals("my+test+id%2F1", uploadId.toString());
  }

  @Test
  public void toStringWithInternalDecoderException() {
    String test = "Invalid % value";
    UploadId id = new UploadId(test);
    assertEquals("Invalid % value", id.toString());
  }

  @Test
  public void equalsSameUrlSafeValue() {
    UploadId id1 = new UploadId("id%2F1");
    UploadId id2 = new UploadId("id/1");
    UploadId id3 = new UploadId("id/1");

    assertEquals(id1, id2);
    assertEquals(id2, id3);
    assertEquals(id1, id1);
    assertNotEquals(id1, null);
    assertNotEquals(id1, UUID.randomUUID());
  }

  @Test
  public void hashCodeSameUrlSafeValue() {
    UploadId id1 = new UploadId("id%2F1");
    UploadId id2 = new UploadId("id/1");
    UploadId id3 = new UploadId("id/1");

    assertEquals(id1.hashCode(), id2.hashCode());
    assertEquals(id2.hashCode(), id3.hashCode());
  }
}
