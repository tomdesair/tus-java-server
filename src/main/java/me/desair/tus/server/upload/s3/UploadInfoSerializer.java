package me.desair.tus.server.upload.s3;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;

/**
 * Utility class responsible for serializing and deserializing {@link UploadInfo} instances to and
 * from JSON format for S3 object metadata storage.
 */
public class UploadInfoSerializer {

  private static final ObjectMapper OBJECT_MAPPER;

  static {
    SimpleModule module = new SimpleModule();
    module.addSerializer(
        UploadId.class,
        new JsonSerializer<UploadId>() {
          @Override
          public void serialize(
              UploadId uploadId, JsonGenerator gen, SerializerProvider serializers)
              throws IOException {
            gen.writeString(uploadId.toString());
          }
        });

    module.addDeserializer(
        UploadId.class,
        new JsonDeserializer<UploadId>() {
          @Override
          public UploadId deserialize(JsonParser p, DeserializationContext ctxt)
              throws IOException {
            String text = p.getText();
            if (text == null || text.isEmpty()) {
              return null;
            }
            return new UploadId(text);
          }
        });

    OBJECT_MAPPER =
        new ObjectMapper()
            .registerModule(module)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  private UploadInfoSerializer() {
    // Utility class
  }

  /**
   * Serialize the given {@link UploadInfo} object to a JSON string.
   *
   * @param uploadInfo The upload info object to serialize
   * @return A JSON string representation of the upload info
   * @throws IOException If serialization fails
   */
  public static String serialize(UploadInfo uploadInfo) throws IOException {
    if (uploadInfo == null) {
      return null;
    }
    return OBJECT_MAPPER.writeValueAsString(uploadInfo);
  }

  /**
   * Deserialize an {@link UploadInfo} object from a JSON string.
   *
   * @param json The JSON string representation of the upload info
   * @return The deserialized UploadInfo instance, or null if the input is blank
   * @throws IOException If deserialization fails
   */
  public static UploadInfo deserialize(String json) throws IOException {
    if (json == null || json.trim().isEmpty()) {
      return null;
    }
    return OBJECT_MAPPER.readValue(json, UploadInfo.class);
  }

  /**
   * Deserialize an {@link UploadInfo} object from an {@link InputStream}.
   *
   * @param inputStream The input stream containing the JSON data
   * @return The deserialized UploadInfo instance
   * @throws IOException If deserialization fails
   */
  public static UploadInfo deserialize(InputStream inputStream) throws IOException {
    if (inputStream == null) {
      return null;
    }
    return OBJECT_MAPPER.readValue(inputStream, UploadInfo.class);
  }
}
