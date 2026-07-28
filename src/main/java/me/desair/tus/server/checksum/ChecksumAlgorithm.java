package me.desair.tus.server.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.desair.tus.server.util.StructuredHeaderUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enum that contains all supported checksum algorithms The names of the checksum algorithms MUST
 * only consist of ASCII characters with the modification that uppercase characters are excluded.
 */
public enum ChecksumAlgorithm {
  MD5("MD5", "md5", new String[] {"md5"}, 1),
  SHA1("SHA-1", "sha1", new String[] {"sha", "sha1", "sha-1"}, 2),
  SHA256("SHA-256", "sha256", new String[] {"sha-256"}, 5),
  SHA384("SHA-384", "sha384", new String[] {"sha-384"}, 3),
  SHA512("SHA-512", "sha512", new String[] {"sha-512"}, 4);

  public static final String CHECKSUM_VALUE_SEPARATOR = " ";

  private static final Logger log = LoggerFactory.getLogger(ChecksumAlgorithm.class);

  private final String javaName;
  private final String tusName;
  private final List<String> httpDigestNames;
  private final int priority;

  ChecksumAlgorithm(String javaName, String tusName, String[] httpDigestNames, int priority) {
    this.javaName = javaName;
    this.tusName = tusName;
    this.httpDigestNames = Collections.unmodifiableList(Arrays.asList(httpDigestNames));
    this.priority = priority;
  }

  public String getJavaName() {
    return javaName;
  }

  public String getTusName() {
    return tusName;
  }

  public List<String> getHttpDigestNames() {
    return httpDigestNames;
  }

  public int getPriority() {
    return priority;
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

  /**
   * Retrieves the {@link ChecksumAlgorithm} corresponding to a given RFC 9530 HTTP digest algorithm
   * name (case-insensitive, with whitespace trimmed).
   *
   * @param name The HTTP digest algorithm name
   * @return The matching ChecksumAlgorithm, or null if none matches
   */
  public static ChecksumAlgorithm forHttpDigestName(String name) {
    if (name == null) {
      return null;
    }
    String normalized = name.trim().toLowerCase();
    for (ChecksumAlgorithm alg : ChecksumAlgorithm.values()) {
      for (String digestName : alg.getHttpDigestNames()) {
        if (digestName.equals(normalized)) {
          return alg;
        }
      }
    }
    return null;
  }

  /**
   * Returns a comma-separated string of the supported HTTP digest algorithms for use in the
   * Want-Repr-Digest options header.
   *
   * @return The comma-separated list of digest algorithms
   */
  public static String getSupportedHttpDigestAlgorithmsHeaderValue() {
    List<ChecksumAlgorithm> sorted = new java.util.ArrayList<>(Arrays.asList(values()));
    sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sorted.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(sorted.get(i).getHttpDigestNames().get(0));
    }
    return sb.toString();
  }

  /**
   * Cleans an RFC 9530 structured digest value by stripping surrounding colons.
   *
   * @param val The raw base64 digest string value, possibly colon-wrapped
   * @return The cleaned base64 digest string
   */
  public static String cleanDigestValue(String val) {
    if (val == null) {
      return null;
    }
    String s = val.trim();
    if (s.startsWith(":")) {
      s = s.substring(1);
    }
    if (s.endsWith(":")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  /**
   * Selects the best supported ChecksumAlgorithm from the Want-Repr-Digest header value, taking
   * weight preferences (q) into account.
   *
   * @param wantReprDigest The Want-Repr-Digest header value
   * @return The highest-preference supported algorithm, or null if none matches or wanted
   */
  public static ChecksumAlgorithm selectBestAlgorithm(String wantReprDigest) {
    if (StringUtils.isBlank(wantReprDigest)) {
      return null;
    }

    java.util.List<String> items = StructuredHeaderUtil.parseList(wantReprDigest);
    double bestQ = -1.0;
    ChecksumAlgorithm bestAlg = null;

    for (String item : items) {
      String token = StringUtils.substringBefore(item, ";").trim();
      ChecksumAlgorithm alg = forHttpDigestName(token);
      if (alg != null) {
        double q = 1.0;
        if (item.contains(";")) {
          String paramPart = StringUtils.substringAfter(item, ";").trim();
          if (paramPart.startsWith("q=")) {
            try {
              q = Double.parseDouble(paramPart.substring(2).trim());
            } catch (NumberFormatException e) {
              // Ignore, default to 1.0
            }
          }
        }

        if (q > 0.0) {
          if (q > bestQ) {
            bestQ = q;
            bestAlg = alg;
          } else if (Math.abs(q - bestQ) < 1e-9 && alg.getPriority() > bestAlg.getPriority()) {
            bestAlg = alg;
          }
        }
      }
    }

    return bestAlg;
  }

  /**
   * Parses an RFC 9530 structured digest header value into a map of ChecksumAlgorithm to their
   * cleaned digest values.
   *
   * @param headerValue The raw structured digest header value
   * @return A map of mapped ChecksumAlgorithm to cleaned digest values
   */
  public static Map<ChecksumAlgorithm, String> parseDigestHeader(String headerValue) {
    Map<ChecksumAlgorithm, String> result = new LinkedHashMap<>();
    if (StringUtils.isNotBlank(headerValue)) {
      Map<String, Object> digestDict = StructuredHeaderUtil.parseDictionary(headerValue);
      for (Map.Entry<String, Object> entry : digestDict.entrySet()) {
        ChecksumAlgorithm alg = forHttpDigestName(entry.getKey());
        if (alg != null) {
          result.put(alg, cleanDigestValue((String) entry.getValue()));
        }
      }
    }
    return result;
  }
}
