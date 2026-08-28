package me.desair.tus.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.upload.LeaseData;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link LeaseDataJsonSerializer}. */
public class LeaseDataJsonSerializerTest {

  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("lease-serializer-test-");
  }

  @After
  public void tearDown() throws Exception {
    if (tempDir != null && Files.exists(tempDir)) {
      FileUtils.deleteDirectory(tempDir.toFile());
    }
  }

  @Test
  public void testSerializeAndDeserializeString() throws Exception {
    LeaseData data =
        new LeaseData(
            "holder-123",
            "/files/upload/test-1",
            30000L,
            1700000030000L,
            1700000000000L,
            "/path/to/lock",
            "/path/to/stop");

    String json = LeaseDataJsonSerializer.serialize(data);
    assertNotNull(json);

    LeaseData deserialized = LeaseDataJsonSerializer.deserialize(json);
    assertNotNull(deserialized);
    assertEquals("holder-123", deserialized.getHolderId());
    assertEquals("/files/upload/test-1", deserialized.getRequestUri());
    assertEquals(30000L, deserialized.getLeaseDurationMs());
    assertEquals(1700000030000L, deserialized.getExpiresAt());
    assertEquals(1700000000000L, deserialized.getAcquiredAt());
    assertEquals("/path/to/lock", deserialized.getLockPath());
    assertEquals("/path/to/stop", deserialized.getStopPath());
  }

  @Test
  public void testSerializeAndDeserializeBytesAndStreams() throws Exception {
    LeaseData data = new LeaseData("holder-456", "/files/upload/test-2", 15000L, 200000L);

    byte[] bytes = LeaseDataJsonSerializer.serializeToBytes(data);
    assertNotNull(bytes);

    LeaseData deserialized = LeaseDataJsonSerializer.deserialize(new ByteArrayInputStream(bytes));
    assertNotNull(deserialized);
    assertEquals("holder-456", deserialized.getHolderId());

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    LeaseDataJsonSerializer.serializeToStream(data, baos);
    LeaseData deserializedFromStream =
        LeaseDataJsonSerializer.deserialize(new ByteArrayInputStream(baos.toByteArray()));
    assertNotNull(deserializedFromStream);
    assertEquals("holder-456", deserializedFromStream.getHolderId());
  }

  @Test
  public void testSerializeAndDeserializeFilesAndPaths() throws Exception {
    LeaseData data = new LeaseData("holder-file", "/files/upload/test-file", 10000L, 300000L);
    Path filePath = tempDir.resolve("lease.json");
    File file = filePath.toFile();

    LeaseDataJsonSerializer.serializeToPath(data, filePath);
    LeaseData deserializedPath = LeaseDataJsonSerializer.deserialize(filePath);
    assertNotNull(deserializedPath);
    assertEquals("holder-file", deserializedPath.getHolderId());

    Path relPath = Paths.get("target", "temp-rel-" + java.util.UUID.randomUUID() + ".json");
    try {
      LeaseDataJsonSerializer.serializeToPath(data, relPath);
      LeaseData deserializedRel = LeaseDataJsonSerializer.deserialize(relPath);
      assertNotNull(deserializedRel);
      assertEquals("holder-file", deserializedRel.getHolderId());
    } finally {
      Files.deleteIfExists(relPath);
    }

    LeaseDataJsonSerializer.serializeToFile(data, file);
    LeaseData deserializedFile = LeaseDataJsonSerializer.deserialize(file);
    assertNotNull(deserializedFile);
    assertEquals("holder-file", deserializedFile.getHolderId());
  }

  @Test
  public void testForwardCompatibilityUnknownProperties() throws Exception {
    String jsonWithExtra =
        "{\"holderId\":\"holder-extra\",\"futureProperty\":\"new-feature-value\"}";
    LeaseData deserialized = LeaseDataJsonSerializer.deserialize(jsonWithExtra);
    assertNotNull(deserialized);
    assertEquals("holder-extra", deserialized.getHolderId());
  }

  @Test
  public void testNullAndEmptyInputs() throws Exception {
    assertNull(LeaseDataJsonSerializer.serialize(null));
    assertNull(LeaseDataJsonSerializer.serializeToBytes(null));
    assertNull(LeaseDataJsonSerializer.deserialize((String) null));
    assertNull(LeaseDataJsonSerializer.deserialize((InputStream) null));
    assertNull(LeaseDataJsonSerializer.deserialize((File) null));
    assertNull(LeaseDataJsonSerializer.deserialize((Path) null));
    assertNull(LeaseDataJsonSerializer.deserialize(""));
    assertNull(LeaseDataJsonSerializer.deserialize("   "));
  }

  @Test(expected = IOException.class)
  public void testInvalidJsonThrowsIOException() throws Exception {
    LeaseDataJsonSerializer.deserialize("invalid-json-{");
  }

  @Test
  public void testEqualsAndHashCode() {
    LeaseData d1 = new LeaseData("h1", "/u1", 1000L, 2000L, 500L, "/lock1", "/stop1");
    LeaseData d2 = new LeaseData("h1", "/u1", 1000L, 2000L, 500L, "/lock1", "/stop1");
    LeaseData d3 = new LeaseData("h2", "/u1", 1000L, 2000L, 500L, "/lock1", "/stop1");
    LeaseData d4 = new LeaseData("h1", "/u2", 1000L, 2000L, 500L, "/lock1", "/stop1");
    LeaseData d5 = new LeaseData("h1", "/u1", 2000L, 2000L, 500L, "/lock1", "/stop1");
    LeaseData d6 = new LeaseData("h1", "/u1", 1000L, 3000L, 500L, "/lock1", "/stop1");
    LeaseData d7 = new LeaseData("h1", "/u1", 1000L, 2000L, 600L, "/lock1", "/stop1");
    LeaseData d8 = new LeaseData("h1", "/u1", 1000L, 2000L, 500L, "/lock2", "/stop1");
    LeaseData d9 = new LeaseData("h1", "/u1", 1000L, 2000L, 500L, "/lock1", "/stop2");

    assertTrue(d1.equals(d1));
    assertTrue(d1.equals(d2));
    assertEquals(d1.hashCode(), d2.hashCode());

    assertFalse(d1.equals(null));
    assertFalse(d1.equals("not-a-lease-data"));
    assertFalse(d1.equals(d3));
    assertFalse(d1.equals(d4));
    assertFalse(d1.equals(d5));
    assertFalse(d1.equals(d6));
    assertFalse(d1.equals(d7));
    assertFalse(d1.equals(d8));
    assertFalse(d1.equals(d9));
  }
}
