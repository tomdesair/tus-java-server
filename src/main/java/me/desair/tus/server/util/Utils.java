package me.desair.tus.server.util;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import org.apache.commons.io.serialization.ValidatingObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class that contains various static helper methods */
public class Utils {

  private static final Logger log = LoggerFactory.getLogger(Utils.class);
  private static final int LOCK_FILE_RETRY_COUNT = 3;
  private static final long LOCK_FILE_SLEEP_TIME = 500;
  private static final Pattern CHECKSUM_VALUE_PATTERN = Pattern.compile("^[a-zA-Z0-9+/=\\-_]+$");

  private Utils() {
    // This is a utility class that only holds static utility methods
  }

  public static String getHeader(HttpServletRequest request, String header) {
    return StringUtils.trimToEmpty(request.getHeader(header));
  }

  public static Long getLongHeader(HttpServletRequest request, String header) {
    try {
      return Long.valueOf(getHeader(request, header));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Build a comma-separated list based on the remote address of the request and the
   * X-Forwareded-For header. The list is constructed as "client, proxy1, proxy2".
   *
   * @return A comma-separated list of ip-addresses
   */
  public static String buildRemoteIpList(HttpServletRequest servletRequest) {
    String ipAddresses = servletRequest.getRemoteAddr();
    String xforwardedForHeader = getHeader(servletRequest, HttpHeader.X_FORWARDED_FOR);
    if (xforwardedForHeader.length() > 0) {
      ipAddresses = xforwardedForHeader + ", " + ipAddresses;
    }
    return ipAddresses;
  }

  public static List<String> parseConcatenationIDsFromHeader(String uploadConcatValue) {
    List<String> output = new LinkedList<>();

    String idString = StringUtils.substringAfter(uploadConcatValue, ";");
    for (String id : StringUtils.trimToEmpty(idString).split("\\s")) {
      output.add(id);
    }

    return output;
  }

  public static <T> T readSerializable(Path path, Class<T> clazz) throws IOException {
    T info = null;
    if (path != null) {
      try (FileChannel channel = FileChannel.open(path, READ)) {
        // Lock will be released when the channel is closed
        if (lockFileShared(channel) != null) {

          try (ValidatingObjectInputStream ois = new ValidatingObjectInputStream(Channels.newInputStream(channel))) {
            ois.accept(
                me.desair.tus.server.upload.UploadInfo.class,
                me.desair.tus.server.upload.UploadType.class,
                me.desair.tus.server.upload.UploadId.class,
                me.desair.tus.server.checksum.ChecksumAlgorithm.class,
                java.util.UUID.class,
                java.lang.Long.class,
                java.lang.String.class,
                java.lang.Number.class,
                java.lang.Enum.class,
                ArrayList.class,
                LinkedList.class,
                java.util.List.class,
                Arrays.asList("").getClass(),
                String[].class);
            // For testing purposes: allows tests to deserialize their own mock objects
            ois.accept("me.desair.tus.server.util.UtilsTest$TestSerializable");

            info = clazz.cast(ois.readObject());
          } catch (ClassNotFoundException
              | java.io.EOFException
              | java.io.StreamCorruptedException e) {
            // File may be corrupted due to unexpected server shutdown
            log.warn("Unable to read serializable file {}: {}", path, e.getMessage());
            info = null;
          }
        } else {
          throw new IOException("Unable to lock file " + path);
        }
      }
    }
    return info;
  }

  public static void writeSerializable(Serializable object, Path path) throws IOException {
    if (path != null) {
      try (FileChannel channel = FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING)) {
        // Lock will be released when the channel is closed
        if (lockFileExclusively(channel) != null) {

          try (OutputStream buffer = new BufferedOutputStream(Channels.newOutputStream(channel));
              ObjectOutput output = new ObjectOutputStream(buffer)) {

            output.writeObject(object);
          }
        } else {
          throw new IOException("Unable to lock file " + path);
        }
      }
    }
  }

  public static FileLock lockFileExclusively(FileChannel channel) throws IOException {
    return lockFile(channel, false);
  }

  public static FileLock lockFileShared(FileChannel channel) throws IOException {
    return lockFile(channel, true);
  }

  /**
   * Sleep the specified number of milliseconds
   *
   * @param sleepTimeMillis The time to sleep in milliseconds
   */
  public static void sleep(long sleepTimeMillis) {
    try {
      Thread.sleep(sleepTimeMillis);
    } catch (InterruptedException e) {
      log.warn("Sleep was interrupted");
      // Restore interrupted state...
      Thread.currentThread().interrupt();
    }
  }

  private static FileLock lockFile(FileChannel channel, boolean shared) throws IOException {
    int i = 0;
    FileLock lock = null;
    do {
      if (i > 0) {
        sleep(LOCK_FILE_SLEEP_TIME);
      }

      lock = channel.tryLock(0L, Long.MAX_VALUE, shared);

      i++;
    } while (lock == null && i < LOCK_FILE_RETRY_COUNT);

    return lock;
  }

  /** Helper class to store parsed checksum header information. */
  public static class ChecksumInfo {
    private final ChecksumAlgorithm algorithm;
    private final String value;

    public ChecksumInfo(ChecksumAlgorithm algorithm, String value) {
      this.algorithm = algorithm;
      this.value = value;
    }

    public ChecksumAlgorithm getAlgorithm() {
      return algorithm;
    }

    public String getValue() {
      return value;
    }
  }

  /**
   * Parse the Upload-Checksum header from the HTTP request.
   *
   * @param request The HttpServletRequest
   * @return ChecksumInfo if header is present and valid, null otherwise
   */
  public static ChecksumInfo parseUploadChecksumHeader(HttpServletRequest request) {
    String uploadChecksumHeader = request.getHeader(HttpHeader.UPLOAD_CHECKSUM);
    if (StringUtils.isNotBlank(uploadChecksumHeader)) {
      ChecksumAlgorithm algorithm = ChecksumAlgorithm.forUploadChecksumHeader(uploadChecksumHeader);
      String checksumValue =
          StringUtils.substringAfter(
              uploadChecksumHeader, ChecksumAlgorithm.CHECKSUM_VALUE_SEPARATOR);
      if (algorithm != null
          && StringUtils.isNotBlank(checksumValue)
          && CHECKSUM_VALUE_PATTERN.matcher(checksumValue).matches()) {
        return new ChecksumInfo(algorithm, checksumValue);
      }
    }
    return null;
  }
}
