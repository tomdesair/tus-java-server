package me.desair.tus.server.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import me.desair.tus.server.upload.LeaseData;

/**
 * Utility class responsible for serializing and deserializing {@link LeaseData} objects to and from
 * JSON format across disk-based and S3-based distributed locking services.
 *
 * <p>Configured with {@code FAIL_ON_UNKNOWN_PROPERTIES = false} to ensure forward and backward
 * compatibility when adding or modifying lease properties.
 */
public class LeaseDataJsonSerializer {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private LeaseDataJsonSerializer() {
    // Utility class
  }

  /**
   * Serialize the given {@link LeaseData} object to a JSON string.
   *
   * @param data The lease data to serialize
   * @return A JSON string representation of the data, or null if input is null
   * @throws IOException If serialization fails
   */
  public static String serialize(LeaseData data) throws IOException {
    if (data == null) {
      return null;
    }
    return OBJECT_MAPPER.writeValueAsString(data);
  }

  /**
   * Serialize the given {@link LeaseData} object directly to a UTF-8 byte array.
   *
   * @param data The lease data to serialize
   * @return A UTF-8 byte array containing the JSON data, or null if input is null
   * @throws IOException If serialization fails
   */
  public static byte[] serializeToBytes(LeaseData data) throws IOException {
    if (data == null) {
      return null;
    }
    return OBJECT_MAPPER.writeValueAsBytes(data);
  }

  /**
   * Serialize the given {@link LeaseData} object directly to an {@link OutputStream}.
   *
   * @param data The lease data to serialize
   * @param outputStream The target output stream
   * @throws IOException If serialization fails
   */
  public static void serializeToStream(LeaseData data, OutputStream outputStream)
      throws IOException {
    if (data != null && outputStream != null) {
      OBJECT_MAPPER.writeValue(outputStream, data);
    }
  }

  /**
   * Serialize the given {@link LeaseData} object directly to a {@link File}.
   *
   * @param data The lease data to serialize
   * @param file The target destination file
   * @throws IOException If serialization fails
   */
  public static void serializeToFile(LeaseData data, File file) throws IOException {
    if (data != null && file != null) {
      OBJECT_MAPPER.writeValue(file, data);
    }
  }

  /**
   * Serialize the given {@link LeaseData} object directly to a {@link Path}.
   *
   * @param data The lease data to serialize
   * @param path The target destination path
   * @throws IOException If serialization fails
   */
  public static void serializeToPath(LeaseData data, Path path) throws IOException {
    if (data != null && path != null) {
      serializeToFile(data, path.toFile());
    }
  }

  /**
   * Deserialize a {@link LeaseData} object from a JSON string.
   *
   * @param json The JSON string representation of the lease data
   * @return The deserialized LeaseData instance, or null if input is blank
   * @throws IOException If deserialization fails
   */
  public static LeaseData deserialize(String json) throws IOException {
    if (json == null || json.trim().isEmpty()) {
      return null;
    }
    return OBJECT_MAPPER.readValue(json, LeaseData.class);
  }

  /**
   * Deserialize a {@link LeaseData} object from an {@link InputStream}.
   *
   * @param inputStream The input stream containing the JSON data
   * @return The deserialized LeaseData instance, or null if input stream is null
   * @throws IOException If deserialization fails
   */
  public static LeaseData deserialize(InputStream inputStream) throws IOException {
    if (inputStream == null) {
      return null;
    }
    return OBJECT_MAPPER.readValue(inputStream, LeaseData.class);
  }

  /**
   * Deserialize a {@link LeaseData} object from a {@link File}.
   *
   * @param file The file containing the JSON data
   * @return The deserialized LeaseData instance, or null if file is null or doesn't exist
   * @throws IOException If deserialization fails
   */
  public static LeaseData deserialize(File file) throws IOException {
    if (file == null || !file.exists() || file.length() == 0) {
      return null;
    }
    return OBJECT_MAPPER.readValue(file, LeaseData.class);
  }

  /**
   * Deserialize a {@link LeaseData} object from a {@link Path}.
   *
   * @param path The path containing the JSON data
   * @return The deserialized LeaseData instance, or null if path is null or doesn't exist
   * @throws IOException If deserialization fails
   */
  public static LeaseData deserialize(Path path) throws IOException {
    if (path == null) {
      return null;
    }
    return deserialize(path.toFile());
  }
}
