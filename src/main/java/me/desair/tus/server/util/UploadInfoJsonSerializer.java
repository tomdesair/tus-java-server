package me.desair.tus.server.util;

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
import java.io.OutputStream;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;

/**
 * Utility class responsible for serializing and deserializing {@link UploadInfo} and other domain
 * objects to and from JSON format using Jackson.
 */
public class UploadInfoJsonSerializer {

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

  private UploadInfoJsonSerializer() {
    // Utility class
  }

  /**
   * Serialize the given object to a JSON string.
   *
   * @param object The object to serialize
   * @return A JSON string representation of the object
   * @throws IOException If serialization fails
   */
  public static String serialize(Object object) throws IOException {
    if (object == null) {
      return null;
    }
    return OBJECT_MAPPER.writeValueAsString(object);
  }

  /**
   * Serialize the given object directly to an {@link OutputStream}.
   *
   * @param object The object to serialize
   * @param outputStream The target output stream
   * @throws IOException If serialization fails
   */
  public static void serializeToStream(Object object, OutputStream outputStream)
      throws IOException {
    if (object != null && outputStream != null) {
      OBJECT_MAPPER.writeValue(outputStream, object);
    }
  }

  /**
   * Deserialize an {@link UploadInfo} object from a JSON string.
   *
   * @param json The JSON string representation of the upload info
   * @return The deserialized UploadInfo instance, or null if input is blank
   * @throws IOException If deserialization fails
   */
  public static UploadInfo deserialize(String json) throws IOException {
    return deserialize(json, UploadInfo.class);
  }

  /**
   * Deserialize an object of the specified class from a JSON string.
   *
   * @param <T> The target object type
   * @param json The JSON string representation
   * @param clazz The target class type
   * @return The deserialized instance, or null if input is blank
   * @throws IOException If deserialization fails
   */
  public static <T> T deserialize(String json, Class<T> clazz) throws IOException {
    if (json == null || json.trim().isEmpty() || clazz == null) {
      return null;
    }
    return OBJECT_MAPPER.readValue(json, clazz);
  }

  /**
   * Deserialize an {@link UploadInfo} object from an {@link InputStream}.
   *
   * @param inputStream The input stream containing the JSON data
   * @return The deserialized UploadInfo instance, or null if input stream is null
   * @throws IOException If deserialization fails
   */
  public static UploadInfo deserialize(InputStream inputStream) throws IOException {
    return deserialize(inputStream, UploadInfo.class);
  }

  /**
   * Deserialize an object of the specified class from an {@link InputStream}.
   *
   * @param <T> The target object type
   * @param inputStream The input stream containing the JSON data
   * @param clazz The target class type
   * @return The deserialized instance, or null if input stream is null
   * @throws IOException If deserialization fails
   */
  public static <T> T deserialize(InputStream inputStream, Class<T> clazz) throws IOException {
    if (inputStream == null || clazz == null) {
      return null;
    }
    return OBJECT_MAPPER.readValue(inputStream, clazz);
  }
}
