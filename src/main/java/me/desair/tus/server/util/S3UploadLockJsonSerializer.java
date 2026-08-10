package me.desair.tus.server.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import me.desair.tus.server.upload.s3.S3UploadLock;

/**
 * Utility class responsible for serializing and deserializing {@link S3UploadLock} objects to and
 * from JSON format using Jackson.
 *
 * <p>Configured with {@code FAIL_ON_UNKNOWN_PROPERTIES = false} to ensure forward and backward
 * compatibility when adding or modifying lock properties across versions.
 */
public class S3UploadLockJsonSerializer {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private S3UploadLockJsonSerializer() {
    // Utility class
  }

  /**
   * Serialize the given object to a JSON string.
   *
   * @param object The object to serialize
   * @return A JSON string representation of the object, or null if object is null
   * @throws IOException If serialization fails
   */
  public static String serialize(Object object) throws IOException {
    if (object == null) {
      return null;
    }
    return OBJECT_MAPPER.writeValueAsString(object);
  }

  /**
   * Serialize the given object directly to a byte array.
   *
   * @param object The object to serialize
   * @return A UTF-8 byte array containing the JSON data, or null if object is null
   * @throws IOException If serialization fails
   */
  public static byte[] serializeToBytes(Object object) throws IOException {
    if (object == null) {
      return null;
    }
    return OBJECT_MAPPER.writeValueAsBytes(object);
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
   * Deserialize an {@link S3UploadLock} object from a JSON string.
   *
   * @param json The JSON string representation of the lock data
   * @return The deserialized S3UploadLock instance, or null if input is blank
   * @throws IOException If deserialization fails
   */
  public static S3UploadLock deserialize(String json) throws IOException {
    return deserialize(json, S3UploadLock.class);
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
   * Deserialize an {@link S3UploadLock} object from an {@link InputStream}.
   *
   * @param inputStream The input stream containing the JSON data
   * @return The deserialized S3UploadLock instance, or null if input stream is null
   * @throws IOException If deserialization fails
   */
  public static S3UploadLock deserialize(InputStream inputStream) throws IOException {
    return deserialize(inputStream, S3UploadLock.class);
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
