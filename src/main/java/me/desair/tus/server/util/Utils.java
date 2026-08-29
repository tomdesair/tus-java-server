package me.desair.tus.server.util;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.io.serialization.ValidatingObjectInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
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

  /**
   * Reads a serializable object from disk.
   *
   * @param <T> Target object type
   * @param path The file path to read from
   * @param clazz The target object class
   * @return Deserialized object instance, or null if reading fails or file does not exist
   * @throws IOException If file access fails
   */
  public static <T> T readSerializable(Path path, Class<T> clazz) throws IOException {
    T info = null;
    if (path != null && Files.exists(path)) {
      try (InputStream is = Files.newInputStream(path);
          ValidatingObjectInputStream ois = new ValidatingObjectInputStream(is)) {
        ois.accept("java.lang.*", "java.util.*", "me.desair.tus.server.*");
        info = clazz.cast(ois.readObject());
      } catch (ClassNotFoundException | java.io.EOFException | java.io.StreamCorruptedException e) {
        // File may be corrupted due to unexpected server shutdown
        log.warn("Unable to read serializable file {}: {}", path, e.getMessage());
        info = null;
      }
    }
    return info;
  }

  /**
   * Deletes a file or directory if it exists, quietly ignoring any exceptions that occur.
   *
   * @param path The path to delete
   * @return {@code true} if the file was deleted; {@code false} if it did not exist or deletion
   *     failed
   */
  public static boolean deletePathQuietly(Path path) {
    if (path == null) {
      return false;
    }
    try {
      return Files.deleteIfExists(path);
    } catch (Exception e) {
      log.debug("Failed to delete path quietly: {}", path, e);
      return false;
    }
  }

  /**
   * Resolves a unique temporary sibling path for a target destination file path.
   *
   * @param targetPath The destination file path
   * @return A temporary sibling path with a unique UUID suffix, or null if targetPath is null
   */
  public static Path createTempSiblingPath(Path targetPath) {
    if (targetPath == null) {
      return null;
    }
    Path parent = targetPath.getParent();
    String tempFileName = targetPath.getFileName().toString() + ".tmp." + UUID.randomUUID();
    return parent != null ? parent.resolve(tempFileName) : Paths.get(tempFileName);
  }

  /**
   * Creates an {@link AutoCloseable} {@link TempPath} that generates a unique temporary sibling
   * path and automatically deletes it upon closing if it still exists.
   *
   * @param targetPath The destination file path
   * @return An AutoCloseable TempPath instance
   */
  public static TempPath createTempSibling(Path targetPath) {
    return new TempPath(targetPath);
  }

  /**
   * Atomically moves a source file to a destination path, replacing any existing destination file.
   *
   * @param source The source file path to move
   * @param destination The target destination file path
   * @throws IOException If moving the file fails
   */
  public static void atomicMove(Path source, Path destination) throws IOException {
    if (source != null && destination != null) {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Writes a serializable object to a file on disk atomically via a temporary file rename.
   *
   * @param object The serializable object to write
   * @param path The file path to write to
   * @throws IOException If file access or move fails
   */
  public static void writeSerializable(Serializable object, Path path) throws IOException {
    if (path != null && object != null) {
      try (TempPath tempPath = new TempPath(path)) {
        try (OutputStream os =
                Files.newOutputStream(tempPath.getPath(), WRITE, CREATE, TRUNCATE_EXISTING);
            OutputStream buffer = new BufferedOutputStream(os);
            ObjectOutput output = new ObjectOutputStream(buffer)) {

          output.writeObject(object);
        }
        atomicMove(tempPath.getPath(), path);
      }
    }
  }

  /**
   * AutoCloseable temporary sibling path that automatically cleans up (deletes) the temporary file
   * upon closing if it still exists.
   */
  public static class TempPath implements AutoCloseable {
    private final Path path;

    /**
     * Constructs a TempPath resolving a unique sibling path for the given target path.
     *
     * @param targetPath The destination file path
     */
    public TempPath(Path targetPath) {
      this.path = createTempSiblingPath(targetPath);
    }

    /**
     * Returns the underlying temporary sibling {@link Path}.
     *
     * @return The temporary path, or null if targetPath was null
     */
    public Path getPath() {
      return path;
    }

    @Override
    public void close() {
      deletePathQuietly(path);
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

  /**
   * Checks whether the given Content-Type header matches the expected media type, ignoring optional
   * parameters (such as ;charset=UTF-8) and casing per RFC 9110 §8.3.
   *
   * @param contentTypeHeader The Content-Type header value from the request
   * @param expectedMediaType The expected media type (e.g. application/offset+octet-stream)
   * @return true if the base media type matches the expected type, false otherwise
   */
  public static boolean isMediaType(String contentTypeHeader, String expectedMediaType) {
    if (contentTypeHeader == null || expectedMediaType == null) {
      return false;
    }
    String baseType = extractMediaType(contentTypeHeader);
    return Strings.CI.equals(baseType, expectedMediaType.trim());
  }

  /**
   * Extracts the base media type from a Content-Type header (the portion before any ';' parameter).
   *
   * @param contentTypeHeader The Content-Type header value
   * @return The trimmed base media type, or null if the header is null
   */
  public static String extractMediaType(String contentTypeHeader) {
    if (contentTypeHeader == null) {
      return null;
    }
    int semicolonIdx = contentTypeHeader.indexOf(';');
    String baseType =
        semicolonIdx >= 0 ? contentTypeHeader.substring(0, semicolonIdx) : contentTypeHeader;
    return baseType.trim();
  }

  /**
   * Resolves the upload URI from the HTTP request and response context.
   *
   * @param method The HttpMethod of the request
   * @param request The TusServletRequest
   * @param response The TusServletResponse
   * @return The upload URI string, or null if it cannot be determined
   */
  public static String getUploadUri(TusServletRequest request, TusServletResponse response) {
    HttpMethod method =
        request != null
            ? HttpMethod.getMethodIfSupported(request, EnumSet.allOf(HttpMethod.class))
            : null;

    if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)) {
      return response != null ? response.getHeader(HttpHeader.LOCATION) : null;
    } else if (request != null) {
      return request.getRequestURI();
    }

    return null;
  }

  /**
   * Detects the active ProtocolVersion for an incoming HttpServletRequest.
   *
   * @param request The current HttpServletRequest
   * @param supportedProtocolVersion The configured ProtocolVersion setting
   * @return The detected ProtocolVersion (TUS_1_0_0 or RUFH)
   */
  public static ProtocolVersion detectProtocolVersion(
      HttpServletRequest request, ProtocolVersion supportedProtocolVersion) {
    if (supportedProtocolVersion == ProtocolVersion.TUS_1_0_0) {
      return ProtocolVersion.TUS_1_0_0;
    }
    if (supportedProtocolVersion == ProtocolVersion.RUFH) {
      return ProtocolVersion.RUFH;
    }

    if (request != null) {
      if (StringUtils.isNotBlank(request.getHeader(HttpHeader.TUS_RESUMABLE))) {
        return ProtocolVersion.TUS_1_0_0;
      }
      if (StringUtils.isNotBlank(request.getHeader(HttpHeader.UPLOAD_OFFSET))
          || StringUtils.isNotBlank(request.getHeader(HttpHeader.UPLOAD_COMPLETE))
          || StringUtils.isNotBlank(request.getHeader(HttpHeader.UPLOAD_DRAFT))
          || StringUtils.isNotBlank(request.getHeader("upload-draft-interop-version"))
          || isMediaType(
              request.getHeader(HttpHeader.CONTENT_TYPE), HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD)
          || isMediaType(
              request.getHeader(HttpHeader.CONTENT_TYPE),
              HttpHeader.CONTENT_TYPE_OFFSET_OCTET_STREAM)) {
        return ProtocolVersion.RUFH;
      }
      String method = request.getMethod();
      if (HttpMethod.HEAD.name().equalsIgnoreCase(method)
          || HttpMethod.GET.name().equalsIgnoreCase(method)
          || HttpMethod.DELETE.name().equalsIgnoreCase(method)) {
        return ProtocolVersion.RUFH;
      }
    }

    return ProtocolVersion.TUS_1_0_0;
  }

  /**
   * Extracts the path component from an upload URI string, which may be a relative path (e.g.,
   * "/files") or an absolute URL (e.g., "https://example.com/files").
   *
   * @param uploadUri The upload URI or URL string
   * @return The path component starting with "/", or "/" if none is present
   */
  public static String extractUriPath(String uploadUri) {
    if (StringUtils.isBlank(uploadUri)) {
      return "/";
    }
    // For absolute URLs (http:// or https://), extract the path starting after the authority
    // component
    if (Strings.CS.startsWith(uploadUri, "http://")
        || Strings.CS.startsWith(uploadUri, "https://")) {
      int schemeEnd = uploadUri.indexOf("://");
      int pathStart = uploadUri.indexOf('/', schemeEnd + 3);
      if (pathStart == -1) {
        return "/";
      }
      return uploadUri.substring(pathStart);
    }
    return uploadUri;
  }

  /**
   * Extracts the origin component (scheme + host + port) from an upload URL string, or an empty
   * string if the URI is relative or blank.
   *
   * @param uploadUri The upload URI or URL string
   * @return The origin string (e.g. "https://example.com:8080"), or "" if uploadUri is relative or
   *     blank
   */
  public static String extractUriOrigin(String uploadUri) {
    if (StringUtils.isBlank(uploadUri)) {
      return "";
    }
    // Extract scheme + host[:port] for absolute HTTP and HTTPS URLs
    if (Strings.CS.startsWith(uploadUri, "http://")
        || Strings.CS.startsWith(uploadUri, "https://")) {
      int schemeEnd = uploadUri.indexOf("://");
      int pathStart = uploadUri.indexOf('/', schemeEnd + 3);
      if (pathStart == -1) {
        return uploadUri;
      }
      return uploadUri.substring(0, pathStart);
    }
    return "";
  }

  /**
   * Determine if the given HTTP servlet request targets the upload creation base URI endpoint.
   *
   * @param request The HTTP request
   * @param uploadStorageService The storage service instance
   * @return {@code true} if request targets the base creation endpoint URI; {@code false} otherwise
   */
  public static boolean isCreationEndpoint(
      HttpServletRequest request, UploadStorageService uploadStorageService) {
    if (request == null || uploadStorageService == null) {
      return false;
    }
    String requestUri = request.getRequestURI();
    String baseUri = extractUriPath(uploadStorageService.getUploadUri());
    return requestUri != null
        && baseUri != null
        && (requestUri.equals(baseUri) || requestUri.equals(baseUri + "/"));
  }

  /**
   * Determine if the given HTTP servlet request target URI represents an existing upload resource.
   *
   * @param request The HTTP request
   * @param uploadStorageService The storage service instance
   * @param ownerKey The owner key
   * @return {@code true} if the request targets an existing upload resource; {@code false}
   *     otherwise
   * @throws IOException If storage lookup encounters an IO error
   */
  public static boolean isExistingUploadResource(
      HttpServletRequest request, UploadStorageService uploadStorageService, String ownerKey)
      throws IOException {
    if (isCreationEndpoint(request, uploadStorageService)) {
      return false;
    }
    String requestUri = request != null ? request.getRequestURI() : null;
    UploadInfo existingUpload =
        (uploadStorageService != null && requestUri != null)
            ? uploadStorageService.getUploadInfo(requestUri, ownerKey)
            : null;
    return existingUpload != null && !existingUpload.isExpired();
  }

  /**
   * Builds the upload location URI for a newly created upload resource.
   *
   * @param uploadInfo The UploadInfo object containing the upload ID (must not be null and must
   *     have an ID)
   * @param servletRequest The current HttpServletRequest or TusServletRequest
   * @param storageService The current UploadStorageService (must not be null and must have an
   *     upload URI)
   * @return The location URI string for the created upload
   */
  public static String getUploadUriOnCreation(
      UploadInfo uploadInfo,
      HttpServletRequest servletRequest,
      UploadStorageService storageService) {
    Objects.requireNonNull(uploadInfo, "Upload info cannot be null");
    Objects.requireNonNull(uploadInfo.getId(), "Upload ID cannot be null");
    Objects.requireNonNull(storageService, "Storage service cannot be null");
    String configuredUri =
        Objects.requireNonNull(storageService.getUploadUri(), "Upload URI cannot be null");

    String baseUri = configuredUri;

    // When configuredUri contains regex patterns (e.g. /users/[0-9]+/files),
    // resolve the concrete request path dynamically from the incoming servlet request
    boolean hasRegex = configuredUri.contains("[") || configuredUri.contains("(");
    if (hasRegex && servletRequest != null) {
      String origin = extractUriOrigin(configuredUri);
      String requestPath = servletRequest.getRequestURI();
      baseUri = origin + (requestPath.startsWith("/") ? "" : "/") + requestPath;
    }

    // Append the generated upload ID to form the final location URI
    return baseUri + (baseUri.endsWith("/") ? "" : "/") + uploadInfo.getId();
  }

  /**
   * Creates a single-thread scheduled executor service with a daemon thread of the given name.
   *
   * @param threadName The name for the background daemon thread
   * @return A new single-thread ScheduledExecutorService
   */
  public static ScheduledExecutorService createScheduledDaemonExecutor(String threadName) {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, threadName);
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Creates a daemon scheduled executor and immediately schedules a task to run periodically at a
   * fixed rate.
   *
   * @param threadName The name for the background daemon thread
   * @param task The task to execute periodically
   * @param initialDelay The initial delay before the first execution
   * @param period The period between successive executions
   * @param unit The time unit of the initialDelay and period parameters
   * @return The created ScheduledExecutorService
   */
  public static ScheduledExecutorService scheduleWatchdog(
      String threadName, Runnable task, long initialDelay, long period, TimeUnit unit) {
    ScheduledExecutorService executor = createScheduledDaemonExecutor(threadName);
    if (period > 0 && task != null) {
      executor.scheduleAtFixedRate(task, initialDelay, period, unit);
    }
    return executor;
  }

  /**
   * Safely shuts down a ScheduledExecutorService using {@link
   * ScheduledExecutorService#shutdownNow()}.
   *
   * @param executor The ScheduledExecutorService to shut down
   */
  public static void shutdownExecutor(ScheduledExecutorService executor) {
    if (executor != null && !executor.isShutdown()) {
      try {
        executor.shutdownNow();
      } catch (Exception e) {
        log.debug("Error shutting down executor: {}", e.getMessage());
      }
    }
  }

  /**
   * Safely interrupts an input stream if it is an instance of {@link InterruptibleInputStream}, or
   * closes it quietly if it is a standard input stream. Any exceptions encountered during
   * interruption or closing are caught and logged without propagating.
   *
   * @param inputStream The InputStream to interrupt or close
   */
  public static void interruptStream(java.io.InputStream inputStream) {
    if (inputStream == null) {
      return;
    }
    try {
      if (inputStream instanceof InterruptibleInputStream) {
        ((InterruptibleInputStream) inputStream).interrupt();
      } else {
        inputStream.close();
      }
    } catch (Throwable t) {
      log.warn("Error interrupting input stream: {}", t.getMessage(), t);
    }
  }

  /**
   * Safely interrupts and stops a thread, catching and logging any exceptions that occur.
   *
   * @param thread The Thread to interrupt
   */
  public static void interruptThread(Thread thread) {
    if (thread != null) {
      try {
        thread.interrupt();
      } catch (Exception e) {
        log.debug("Error interrupting thread {}: {}", thread.getName(), e.getMessage());
      }
    }
  }

  /**
   * Ensures that the specified directory exists, creating it and any necessary parent directories.
   * If the directory already exists, this method is a safe no-op. If the path is null, this method
   * does nothing.
   *
   * @param dir The directory path to ensure exists
   * @throws IOException If creating the directory hierarchy fails
   */
  public static void ensureDirectoryExists(Path dir) throws IOException {
    if (dir != null && !Files.exists(dir)) {
      Files.createDirectories(dir);
    }
  }

  /**
   * Cleans up temporary files in the specified directory matching a glob pattern and older than
   * maxAgeMillis.
   *
   * @param dir The directory containing temporary files
   * @param globPattern The glob pattern for temporary files to inspect (e.g. "*.tmp")
   * @param maxAgeMillis The maximum age in milliseconds before a file is considered stale and
   *     deleted
   */
  public static void cleanupTempFiles(Path dir, String globPattern, long maxAgeMillis) {
    if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir) || globPattern == null) {
      return;
    }
    long cutoff = System.currentTimeMillis() - maxAgeMillis;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, globPattern)) {
      for (Path file : stream) {
        try {
          if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoff) {
            deletePathQuietly(file);
          }
        } catch (Exception e) {
          log.debug("Error checking temporary file age for {}: {}", file, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.debug("Error scanning directory {} for stale temporary files: {}", dir, e.getMessage());
    }
  }
}
