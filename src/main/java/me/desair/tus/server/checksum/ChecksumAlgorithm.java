package me.desair.tus.server.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enum that contains all supported checksum algorithms The names of the checksum algorithms MUST
 * only consist of ASCII characters with the modification that uppercase characters are excluded.
 */
public enum ChecksumAlgorithm {
  MD5("MD5", "md5"),
  SHA1("SHA-1", "sha1"),
  SHA256("SHA-256", "sha256"),
  SHA384("SHA-384", "sha384"),
  SHA512("SHA-512", "sha512");

  public static final String CHECKSUM_VALUE_SEPARATOR = " ";

  private static final Logger log = LoggerFactory.getLogger(ChecksumAlgorithm.class);

  private String javaName;
  private String tusName;

  ChecksumAlgorithm(String javaName, String tusName) {
    this.javaName = javaName;
    this.tusName = tusName;
  }

  public String getJavaName() {
    return javaName;
  }

  public String getTusName() {
    return tusName;
  }

  @Override
  public String toString() {
    return getTusName();
  }

  public MessageDigest getMessageDigest() {
    try {
      return MessageDigest.getInstance(getJavaName());
    } catch (NoSuchAlgorithmException e) {
      log.error("We are trying the use an algorithm that is not supported by this JVM", e);
      return null;
    }
  }

  public static ChecksumAlgorithm forTusName(String name) {
    for (ChecksumAlgorithm alg : ChecksumAlgorithm.values()) {
      if (alg.getTusName().equals(name)) {
        return alg;
      }
    }
    return null;
  }

  public static ChecksumAlgorithm forUploadChecksumHeader(String uploadChecksumHeader) {
    String algorithm = StringUtils.substringBefore(uploadChecksumHeader, CHECKSUM_VALUE_SEPARATOR);
    return forTusName(algorithm);
  }
}
