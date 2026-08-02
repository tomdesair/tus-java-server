package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Test;

public class UploadInfoSerializerTest {

  @Test
  public void testSerializeAndDeserializeUploadInfo() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1024L);
    info.setOffset(512L);
    info.setOwnerKey("owner-1");
    info.setStorageUploadId("custom-storage-id");

    String json = UploadInfoSerializer.serialize(info);
    assertNotNull(json);

    UploadInfo deserialized = UploadInfoSerializer.deserialize(json);
    assertNotNull(deserialized);
    assertEquals("24249a5b-01a4-4bf8-b67a-364273bb5a2e", deserialized.getId().toString());
    assertEquals(Long.valueOf(1024L), deserialized.getLength());
    assertEquals(Long.valueOf(512L), deserialized.getOffset());
    assertEquals("owner-1", deserialized.getOwnerKey());
    assertEquals("custom-storage-id", deserialized.getStorageUploadId());

    // Test InputStream overload
    UploadInfo fromStream =
        UploadInfoSerializer.deserialize(
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    assertNotNull(fromStream);
    assertEquals("24249a5b-01a4-4bf8-b67a-364273bb5a2e", fromStream.getId().toString());
  }

  @Test
  public void testNullAndEmptyHandling() throws Exception {
    assertNull(UploadInfoSerializer.serialize(null));
    assertNull(UploadInfoSerializer.deserialize((String) null));
    assertNull(UploadInfoSerializer.deserialize((InputStream) null));
    assertNull(UploadInfoSerializer.deserialize(""));

    UploadInfo emptyIdInfo = UploadInfoSerializer.deserialize("{\"id\":\"\"}");
    assertNotNull(emptyIdInfo);
    assertNull(emptyIdInfo.getId());

    try {
      UploadInfoSerializer.deserialize("invalid-json");
    } catch (Exception expected) {
      // expected
    }
  }
}
