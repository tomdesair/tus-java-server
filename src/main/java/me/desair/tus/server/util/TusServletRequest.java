package me.desair.tus.server.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.TusExtension;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public class TusServletRequest extends HttpServletRequestWrapper {

  private BoundedInputStream countingInputStream;
  private Map<ChecksumAlgorithm, DigestInputStream> digestInputStreamMap =
      new EnumMap<>(ChecksumAlgorithm.class);

  private InputStream contentInputStream = null;
  private boolean isChunkedTransferDecodingEnabled = true;

  private Map<String, List<String>> trailerHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private Set<String> processedBySet = new TreeSet<>();
  private Boolean hasContent = null;

  /**
   * Constructs a request object wrapping the given request.
   *
   * @param request The upload request we need to wrap
   * @param isChunkedTransferDecodingEnabled Should this request wrapper decode a chunked input
   *     stream
   * @throws IllegalArgumentException if the request is null
   */
  public TusServletRequest(HttpServletRequest request, boolean isChunkedTransferDecodingEnabled) {
    super(request);
    this.isChunkedTransferDecodingEnabled = isChunkedTransferDecodingEnabled;
  }

  /**
   * Constructs a request object wrapping the given request.
   *
   * @param request The upload request we need to wrap
   * @throws IllegalArgumentException if the request is null
   */
  public TusServletRequest(HttpServletRequest request) {
    this(request, false);
  }

  public InputStream getContentInputStream() throws IOException {
    if (contentInputStream == null) {
      contentInputStream = super.getInputStream();
      if (contentInputStream == null) {
        return null;
      }

      // If we're dealing with chunked transfer encoding,
      // abstract it so that the rest of our code doesn't need to care
      boolean isChunked = hasChunkedTransferEncoding();
      if (isChunked && isChunkedTransferDecodingEnabled) {
        contentInputStream = new HttpChunkedEncodingInputStream(contentInputStream, trailerHeaders);
      }

      countingInputStream = BoundedInputStream.builder().setInputStream(contentInputStream).get();
      contentInputStream = countingInputStream;

      // Retrieve checksum algorithm specified in Tus v1 Upload-Checksum header
      ChecksumAlgorithm checksumAlgorithm =
          ChecksumAlgorithm.forUploadChecksumHeader(getHeader(HttpHeader.UPLOAD_CHECKSUM));

      java.util.Set<ChecksumAlgorithm> algorithms = new java.util.LinkedHashSet<>();

      if (isChunked) {
        // Since the Checksum header can still come at the end, keep track of all checksums
        algorithms.addAll(Arrays.asList(ChecksumAlgorithm.values()));
      } else {
        if (checksumAlgorithm != null) {
          algorithms.add(checksumAlgorithm);
        }
        algorithms.addAll(
            ChecksumAlgorithm.parseDigestHeader(getHeader(HttpHeader.CONTENT_DIGEST)).keySet());
        algorithms.addAll(
            ChecksumAlgorithm.parseDigestHeader(getHeader(HttpHeader.WANT_CONTENT_DIGEST))
                .keySet());
      }

      for (ChecksumAlgorithm algorithm : algorithms) {
        DigestInputStream is =
            new DigestInputStream(contentInputStream, algorithm.getMessageDigest());
        digestInputStreamMap.put(algorithm, is);

        contentInputStream = is;
      }
    }

    return contentInputStream;
  }

  public long getBytesRead() {
    return countingInputStream == null ? 0 : countingInputStream.getCount();
  }

  public boolean hasCalculatedChecksum() {
    return !digestInputStreamMap.isEmpty();
  }

  public String getCalculatedChecksum(ChecksumAlgorithm algorithm) {
    MessageDigest messageDigest = getMessageDigest(algorithm);
    return messageDigest == null ? null : Base64.encodeBase64String(messageDigest.digest());
  }

  /**
   * Get the set of checksum algorithms that are actively calculated within this request
   *
   * @return The set of active checksum algorithms
   */
  public Set<ChecksumAlgorithm> getEnabledChecksums() {
    return digestInputStreamMap.keySet();
  }

  /**
   * Determine whether this request carries body content to be uploaded or appended.
   *
   * <p>If {@code Content-Length} is positive, returns {@code true}. If {@code Content-Length} is
   * zero, returns {@code false}. When {@code Content-Length} is absent or negative (e.g. HTTP/1.1
   * chunked transfer encoding, HTTP/2 streaming, or upstream proxies stripping hop-by-hop headers
   * per RFC 9112 §6.3), peeks 1 byte via {@link PushbackInputStream} to verify whether body bytes
   * are present without consuming them from the stream.
   *
   * @return true if the request contains at least one byte of body content, false otherwise
   * @throws IOException if an error occurs reading from the input stream
   */
  public boolean hasContent() throws IOException {
    if (hasContent != null) {
      return hasContent;
    }

    boolean isChunked = hasChunkedTransferEncoding();
    long contentLength = -1L;
    if (!isChunked) {
      contentLength = getContentLengthLong();
      if (contentLength < 0) {
        String clHeader = getHeader(HttpHeader.CONTENT_LENGTH);
        if (StringUtils.isNotBlank(clHeader)) {
          try {
            contentLength = Long.parseLong(clHeader.trim());
          } catch (NumberFormatException ignored) {
            contentLength = -1L;
          }
        }
      }
    }
    if (contentLength > 0) {
      hasContent = Boolean.TRUE;
      return true;
    } else if (contentLength == 0) {
      hasContent = Boolean.FALSE;
      return false;
    }

    InputStream is = getContentInputStream();
    if (is == null) {
      hasContent = Boolean.FALSE;
      return false;
    }

    if (!(is instanceof PushbackInputStream)) {
      is = new PushbackInputStream(is);
      contentInputStream = is;
    }
    PushbackInputStream pushbackStream = (PushbackInputStream) is;
    int firstByte = pushbackStream.read();
    if (firstByte != -1) {
      pushbackStream.unread(firstByte);
      hasContent = Boolean.TRUE;
      return true;
    }

    hasContent = Boolean.FALSE;
    return false;
  }

  @Override
  public String getHeader(String name) {
    String value = super.getHeader(name);

    if (StringUtils.isBlank(value) && trailerHeaders.containsKey(name)) {
      List<String> values = trailerHeaders.get(name);
      if (values != null && !values.isEmpty()) {
        value = values.get(0);
      }
    }

    return value;
  }

  public boolean isProcessedBy(TusExtension processor) {
    return processedBySet.contains(processor.getName());
  }

  public void addProcessor(TusExtension processor) {
    processedBySet.add(processor.getName());
  }

  private boolean hasChunkedTransferEncoding() {
    return Strings.CI.equals("chunked", getHeader(HttpHeader.TRANSFER_ENCODING));
  }

  private MessageDigest getMessageDigest(ChecksumAlgorithm algorithm) {
    if (digestInputStreamMap.containsKey(algorithm)) {
      return digestInputStreamMap.get(algorithm).getMessageDigest();
    } else {
      return null;
    }
  }
}
