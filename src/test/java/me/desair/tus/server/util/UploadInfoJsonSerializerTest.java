package me.desair.tus.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Test;

public class UploadInfoJsonSerializerTest {

  @Test
  public void testSerializeAndDeserializeUploadInfo() throws Exception {
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

    // Test InputStream overload
    UploadInfo fromStream =
        UploadInfoJsonSerializer.deserialize(
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    assertNotNull(fromStream);
    assertEquals("24249a5b-01a4-4bf8-b67a-364273bb5a2e", fromStream.getId().toString());

    // Test OutputStream overload
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    UploadInfoJsonSerializer.serializeToStream(info, baos);
    UploadInfo fromStream2 =
        UploadInfoJsonSerializer.deserialize(new ByteArrayInputStream(baos.toByteArray()));
    assertNotNull(fromStream2);
    assertEquals("24249a5b-01a4-4bf8-b67a-364273bb5a2e", fromStream2.getId().toString());
  }

  @Test
  public void testNullAndEmptyHandling() throws Exception {
    assertNull(UploadInfoJsonSerializer.serialize(null));
    assertNull(UploadInfoJsonSerializer.deserialize((String) null));
    assertNull(UploadInfoJsonSerializer.deserialize((InputStream) null));
    assertNull(UploadInfoJsonSerializer.deserialize(""));

    UploadInfo emptyIdInfo = UploadInfoJsonSerializer.deserialize("{\"id\":\"\"}");
    assertNotNull(emptyIdInfo);
    assertNull(emptyIdInfo.getId());

    try {
      UploadInfoJsonSerializer.deserialize("invalid-json");
    } catch (Exception expected) {
      // expected
    }
  }
}
