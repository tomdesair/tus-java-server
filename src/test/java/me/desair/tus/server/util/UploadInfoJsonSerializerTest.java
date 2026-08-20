package me.desair.tus.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UploadInfoJsonSerializerTest {

  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("upload-info-serializer-test-");
  }

  @After
  public void tearDown() throws Exception {
    if (tempDir != null && Files.exists(tempDir)) {
      FileUtils.deleteDirectory(tempDir.toFile());
    }
  }

  @Test
  public void testSerializeAndDeserializeUploadInfoString() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1024L);
    info.setOffset(512L);
    info.setOwnerKey("owner-1");
    info.setStorageUploadId("custom-storage-id");

    String json = UploadInfoJsonSerializer.serialize(info);
    assertNotNull(json);

    UploadInfo deserialized = UploadInfoJsonSerializer.deserialize(json);
    assertNotNull(deserialized);
    assertEquals("24249a5b-01a4-4bf8-b67a-364273bb5a2e", deserialized.getId().toString());
    assertEquals(Long.valueOf(1024L), deserialized.getLength());
    assertEquals(Long.valueOf(512L), deserialized.getOffset());
    assertEquals("owner-1", deserialized.getOwnerKey());
    assertEquals("custom-storage-id", deserialized.getStorageUploadId());
  }

  @Test
  public void testSerializeAndDeserializeBytesAndStreams() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id-123"));
    info.setLength(2048L);

    byte[] bytes = UploadInfoJsonSerializer.serializeToBytes(info);
    assertNotNull(bytes);

    UploadInfo deserializedBytes = UploadInfoJsonSerializer.deserialize(bytes);
    assertNotNull(deserializedBytes);
    assertEquals("test-id-123", deserializedBytes.getId().toString());

    UploadInfo deserializedBytesGeneric =
        UploadInfoJsonSerializer.deserialize(bytes, UploadInfo.class);
    assertNotNull(deserializedBytesGeneric);
    assertEquals("test-id-123", deserializedBytesGeneric.getId().toString());

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    UploadInfoJsonSerializer.serializeToStream(info, baos);
    UploadInfo fromStream =
        UploadInfoJsonSerializer.deserialize(new ByteArrayInputStream(baos.toByteArray()));
    assertNotNull(fromStream);
    assertEquals("test-id-123", fromStream.getId().toString());

    UploadInfo fromStreamGeneric =
        UploadInfoJsonSerializer.deserialize(
            new ByteArrayInputStream(baos.toByteArray()), UploadInfo.class);
    assertNotNull(fromStreamGeneric);
    assertEquals("test-id-123", fromStreamGeneric.getId().toString());
  }

  @Test
  public void testSerializeAndDeserializeFilesAndPaths() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("file-path-id"));
    info.setLength(4096L);

    Path filePath = tempDir.resolve("upload.info");
    File file = filePath.toFile();

    UploadInfoJsonSerializer.serializeToPath(info, filePath);
    UploadInfo deserializedPath = UploadInfoJsonSerializer.deserialize(filePath);
    assertNotNull(deserializedPath);
    assertEquals("file-path-id", deserializedPath.getId().toString());

    UploadInfo deserializedPathGeneric =
        UploadInfoJsonSerializer.deserialize(filePath, UploadInfo.class);
    assertNotNull(deserializedPathGeneric);
    assertEquals("file-path-id", deserializedPathGeneric.getId().toString());

    UploadInfoJsonSerializer.serializeToFile(info, file);
    UploadInfo deserializedFile = UploadInfoJsonSerializer.deserialize(file);
    assertNotNull(deserializedFile);
    assertEquals("file-path-id", deserializedFile.getId().toString());

    UploadInfo deserializedFileGeneric =
        UploadInfoJsonSerializer.deserialize(file, UploadInfo.class);
    assertNotNull(deserializedFileGeneric);
    assertEquals("file-path-id", deserializedFileGeneric.getId().toString());
  }

  @Test
  public void testGenericClassDeserialization() throws Exception {
    TestModel model = new TestModel("custom-name", 42);
    String json = UploadInfoJsonSerializer.serialize(model);
    assertNotNull(json);

    TestModel deserialized = UploadInfoJsonSerializer.deserialize(json, TestModel.class);
    assertNotNull(deserialized);
    assertEquals("custom-name", deserialized.getName());
    assertEquals(42, deserialized.getValue());
  }

  @Test
  public void testForwardCompatibilityUnknownProperties() throws Exception {
    String jsonWithExtra = "{\"id\":\"known-id\",\"futureField\":\"new-feature-value\"}";
    UploadInfo deserialized = UploadInfoJsonSerializer.deserialize(jsonWithExtra);
    assertNotNull(deserialized);
    assertEquals("known-id", deserialized.getId().toString());
  }

  @Test
  public void testNullAndEmptyHandling() throws Exception {
    assertNull(UploadInfoJsonSerializer.serialize(null));
    assertNull(UploadInfoJsonSerializer.serializeToBytes(null));
    UploadInfoJsonSerializer.serializeToStream(null, null);
    UploadInfoJsonSerializer.serializeToFile(null, null);
    UploadInfoJsonSerializer.serializeToPath(null, null);

    assertNull(UploadInfoJsonSerializer.deserialize((String) null));
    assertNull(UploadInfoJsonSerializer.deserialize((String) null, UploadInfo.class));
    assertNull(UploadInfoJsonSerializer.deserialize(""));
    assertNull(UploadInfoJsonSerializer.deserialize("   "));
    assertNull(UploadInfoJsonSerializer.deserialize("   ", UploadInfo.class));
    assertNull(UploadInfoJsonSerializer.deserialize((byte[]) null));
    assertNull(UploadInfoJsonSerializer.deserialize(new byte[0]));
    assertNull(UploadInfoJsonSerializer.deserialize((byte[]) null, UploadInfo.class));
    assertNull(UploadInfoJsonSerializer.deserialize((InputStream) null));
    assertNull(UploadInfoJsonSerializer.deserialize((InputStream) null, UploadInfo.class));
    assertNull(UploadInfoJsonSerializer.deserialize((File) null));
    assertNull(UploadInfoJsonSerializer.deserialize((File) null, UploadInfo.class));
    assertNull(UploadInfoJsonSerializer.deserialize((Path) null));
    assertNull(UploadInfoJsonSerializer.deserialize((Path) null, UploadInfo.class));

    Path missingPath = tempDir.resolve("missing.info");
    assertNull(UploadInfoJsonSerializer.deserialize(missingPath));
    assertNull(UploadInfoJsonSerializer.deserialize(missingPath.toFile()));

    Path emptyPath = tempDir.resolve("empty.info");
    Files.createFile(emptyPath);
    assertNull(UploadInfoJsonSerializer.deserialize(emptyPath));
    assertNull(UploadInfoJsonSerializer.deserialize(emptyPath.toFile()));

    UploadInfo emptyIdInfo = UploadInfoJsonSerializer.deserialize("{\"id\":\"\"}");
    assertNotNull(emptyIdInfo);
    assertNull(emptyIdInfo.getId());
  }

  @Test(expected = IOException.class)
  public void testInvalidJsonThrowsIOException() throws Exception {
    UploadInfoJsonSerializer.deserialize("invalid-json-{");
  }

  public static class TestModel {
    private String name;
    private int value;

    public TestModel() {}

    public TestModel(String name, int value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getValue() {
      return value;
    }

    public void setValue(int value) {
      this.value = value;
    }
  }
}
