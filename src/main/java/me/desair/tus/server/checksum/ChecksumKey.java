package me.desair.tus.server.checksum;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;

/**
 * Immutable value object representing a normalized checksum key composed of a {@link
 * ChecksumAlgorithm} and a canonical lowercase hexadecimal checksum string.
 *
 * <p>Standardizes incoming checksum strings (which may be provided as hex or Base64) to a safe,
 * filesystem- and object-storage-friendly lowercase hex format, preventing directory traversal or
 * invalid path characters across Linux, Windows, S3, and Azure Blob storage.
 */
public final class ChecksumKey implements Serializable {

  private static final long serialVersionUID = 1L;

  private final ChecksumAlgorithm algorithm;
  private final String hexChecksum;

  private ChecksumKey(ChecksumAlgorithm algorithm, String hexChecksum) {
    this.algorithm = algorithm;
    this.hexChecksum = hexChecksum;
  }

  /**
   * Creates a normalized {@link ChecksumKey} from a raw checksum string and algorithm.
   *
   * @param checksum the raw checksum string (hex or Base64 encoded)
   * @param algorithm the checksum algorithm used
   * @return a canonical {@link ChecksumKey}
   * @throws IllegalArgumentException if the checksum or algorithm is invalid, empty, or unsafe
   */
  public static ChecksumKey of(String checksum, ChecksumAlgorithm algorithm) {
    Objects.requireNonNull(algorithm, "Checksum algorithm must not be null");
    if (checksum == null || checksum.trim().isEmpty()) {
      throw new IllegalArgumentException("Checksum string must not be null or blank");
    }

    String trimmed = checksum.trim();
    String normalizedHex;

    // Direct hexadecimal string detection
    if (trimmed.matches("^[0-9a-fA-F]+$")) {
      normalizedHex = trimmed.toLowerCase(Locale.ROOT);
    } else if (Base64.isBase64(trimmed) && trimmed.matches("^[A-Za-z0-9+/=]+$")) {
      // Decode Base64 encoded checksum values (common in HTTP Content-Digest headers)
      byte[] decoded = Base64.decodeBase64(trimmed);
      if (decoded != null && decoded.length > 0) {
        normalizedHex = Hex.encodeHexString(decoded).toLowerCase(Locale.ROOT);
      } else {
        throw new IllegalArgumentException(
            "The checksum value is not valid hexadecimal or Base64: " + checksum);
      }
    } else {
      throw new IllegalArgumentException(
          "The checksum value is not valid hexadecimal or Base64: " + checksum);
    }

    return new ChecksumKey(algorithm, normalizedHex);
  }

  /**
   * Returns the checksum algorithm.
   *
   * @return the {@link ChecksumAlgorithm}
   */
  public ChecksumAlgorithm getAlgorithm() {
    return algorithm;
  }

  /**
   * Returns the canonical lowercase hexadecimal checksum string.
   *
   * @return the normalized hex checksum
   */
  public String getHexChecksum() {
    return hexChecksum;
  }

  /**
   * Parses a {@link ChecksumKey} from a relative index path formatted as {@code
   * <algorithm>/<checksum>/<parentSuffix>}.
   *
   * @param relativePath The relative path string (e.g. "sha256/abc123hash/parent")
   * @param parentSuffix The expected suffix component (e.g. "parent")
   * @return The parsed {@link ChecksumKey}, or {@code null} if the path is invalid or does not
   *     match
   */
  public static ChecksumKey parseFromIndexRelativePath(String relativePath, String parentSuffix) {
    if (relativePath == null || parentSuffix == null) {
      return null;
    }
    String cleanPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
    String[] parts = cleanPath.split("/");
    if (parts.length == 3 && parentSuffix.equals(parts[2])) {
      ChecksumAlgorithm algo = ChecksumAlgorithm.forTusName(parts[0]);
      if (algo != null) {
        try {
          return ChecksumKey.of(parts[1], algo);
        } catch (IllegalArgumentException e) {
          // Malformed checksum value, skip
        }
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ChecksumKey)) {
      return false;
    }
    ChecksumKey that = (ChecksumKey) o;
    return algorithm == that.algorithm && Objects.equals(hexChecksum, that.hexChecksum);
  }

  @Override
  public int hashCode() {
    return Objects.hash(algorithm, hexChecksum);
  }

  @Override
  public String toString() {
    return algorithm.getTusName() + "/" + hexChecksum;
  }
}
