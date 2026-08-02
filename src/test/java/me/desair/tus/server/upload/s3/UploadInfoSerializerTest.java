package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadType;
import org.junit.Test;

public class UploadInfoSerializerTest {

  @Test
  public void testSerializeAndDeserialize() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id-123"));
    info.setLength(104857600L);
    info.setOffset(52428800L);
    info.setOwnerKey("owner-abc");
    info.setStorageUploadId("s3-multipart-id-xyz");
    info.setEncodedMetadata("filename d29ybGQudHh0,filetype dGV4dC9wbGFpbg==");
    info.setChecksum("a3f2b8c1d4e5f6");
    info.setChecksumAlgorithm(ChecksumAlgorithm.SHA256);
    info.setUploadType(UploadType.REGULAR);

    String json = UploadInfoSerializer.serialize(info);
    assertNotNull(json);

    UploadInfo deserialized = UploadInfoSerializer.deserialize(json);
    assertNotNull(deserialized);
    assertEquals(info.getId(), deserialized.getId());
    assertEquals(info.getLength(), deserialized.getLength());
    assertEquals(info.getOffset(), deserialized.getOffset());
    assertEquals(info.getOwnerKey(), deserialized.getOwnerKey());
    assertEquals(info.getStorageUploadId(), deserialized.getStorageUploadId());
    assertEquals(info.getChecksum(), deserialized.getChecksum());
    assertEquals(info.getChecksumAlgorithm(), deserialized.getChecksumAlgorithm());
    assertEquals(info.getUploadType(), deserialized.getUploadType());
  }

  @Test
  public void testDeserializeNullAndEmpty() throws Exception {
    assertNull(UploadInfoSerializer.deserialize((String) null));
    assertNull(UploadInfoSerializer.deserialize(""));
    assertNull(UploadInfoSerializer.deserialize("   "));
  }
}
