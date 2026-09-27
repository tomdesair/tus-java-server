package me.desair.tus.server.util;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.Strings;

/**
 * Utility class for parsing and serializing RFC 9651 Structured Field Values used in IETF Resumable
 * Uploads for HTTP (RUFH).
 *
 * <p>RFC 9651 defines standardized data types for HTTP headers, including:
 *
 * <ul>
 *   <li><b>Booleans (Section 3.3.6)</b>: Encoded as {@code ?1} (true) or {@code ?0} (false).
 *   <li><b>Integers (Section 3.3.1)</b>: Encoded as signed 64-bit decimal integers.
 *   <li><b>Dictionaries (Section 3.2)</b>: Comma-separated list of key-value pairs (e.g. {@code
 *       max-size=100, max-append-size=500}). Keys without explicit values default to boolean {@code
 *       true}.
 * </ul>
 */
public class StructuredHeaderUtil {

  private StructuredHeaderUtil() {
    // Utility class
  }

  /**
   * Parse a Structured Header Boolean item according to RFC 9651 Section 3.3.6.
   *
   * @param headerValue The header value to parse (e.g. "?1" or "?0")
   * @return {@link Boolean#TRUE} for "?1", {@link Boolean#FALSE} for "?0", or {@code null} if
   *     invalid or blank
   */
  public static Boolean parseBoolean(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return null;
    }
    String trimmed = headerValue.trim();
    if (Strings.CS.equals("?1", trimmed)) {
      return Boolean.TRUE;
    } else if (Strings.CS.equals("?0", trimmed)) {
      return Boolean.FALSE;
    }
    return null;
  }

  /**
   * Format a boolean into an RFC 9651 Structured Header Boolean item string.
   *
   * @param value The boolean value to format
   * @return "?1" for true, "?0" for false
   */
  public static String formatBoolean(boolean value) {
    return value ? "?1" : "?0";
  }

  /**
   * Parse a Structured Header Integer item according to RFC 9651 Section 3.3.1.
   *
   * @param headerValue The header value string (e.g. "12500")
   * @return Parsed {@link Long} value, or {@code null} if blank or not a valid decimal integer
   */
  public static Long parseInteger(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(headerValue.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parse a Structured Header Dictionary according to RFC 9651 Section 3.2.
   *
   * <p>Parses comma-separated dictionary members (e.g., {@code max-size=1000000,
   * max-append-size=50000}). Members without explicit values (e.g., {@code key}) implicitly
   * evaluate to boolean {@code true}.
   *
   * @param headerValue The dictionary header value string
   * @return Ordered {@link Map} of member key names to typed values ({@link Long}, {@link Boolean},
   *     or {@link String})
   */
  public static Map<String, Object> parseDictionary(String headerValue) {
    Map<String, Object> dictionary = new LinkedHashMap<>();
    if (headerValue == null || headerValue.isBlank()) {
      return dictionary;
    }

    // Split dictionary members by comma respecting RFC 9651 quoted strings
    java.util.List<String> members = splitMembers(headerValue);
    for (String member : members) {
      int eqIdx = member.indexOf('=');
      if (eqIdx > 0) {
        // Key-value pair member
        String key = member.substring(0, eqIdx).trim();
        String valStr = member.substring(eqIdx + 1).trim();

        // Attempt parsing as structured boolean item first (?1 / ?0)
        Boolean boolVal = parseBoolean(valStr);
        if (boolVal != null) {
          dictionary.put(key, boolVal);
        } else {
          // Attempt parsing as structured integer item
          Long intVal = parseInteger(valStr);
          if (intVal != null) {
            dictionary.put(key, intVal);
          } else {
            // Per RFC 9651 §3.3.3, string values are enclosed in DQUOTEs.
            // Unquote and unescape before sanitizing to preserve intended content.
            dictionary.put(key, sanitizeHeaderValue(unquoteString(valStr)));
          }
        }
      } else {
        // According to RFC 9651 Section 3.2, a member with no value defaults to boolean true
        dictionary.put(member, Boolean.TRUE);
      }
    }
    return dictionary;
  }

  /**
   * Format a string value into an RFC 9651 Structured Header String item (Section 3.3.3), enclosed
   * in DQUOTEs with backslash and quote characters escaped.
   *
   * @param value Raw string value
   * @return RFC 9651 quoted string
   */
  public static String formatString(String value) {
    if (value == null) {
      return "\"\"";
    }
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + sanitizeHeaderValue(escaped) + "\"";
  }

  /**
   * Format a map of key-value limits/entries into an RFC 9651 Structured Header Dictionary string.
   *
   * @param dictionary Map of dictionary keys and values
   * @return Structured header dictionary string (e.g. "max-size=100, max-append-size=50")
   */
  public static String formatDictionary(Map<String, Object> dictionary) {
    if (dictionary == null || dictionary.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Object> entry : dictionary.entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(sanitizeHeaderValue(entry.getKey())).append("=");
      Object val = entry.getValue();
      if (val instanceof Boolean) {
        sb.append(formatBoolean((Boolean) val));
      } else {
        sb.append(sanitizeHeaderValue(val.toString()));
      }
    }
    return sb.toString();
  }

  /**
   * Sanitize header input to prevent CRLF injection and HTTP response splitting attacks. Strips CR
   * ('\r'), LF ('\n'), and null ('\0') characters.
   *
   * @param value Raw string input
   * @return Sanitized string with CR/LF/null characters removed
   */
  public static String sanitizeHeaderValue(String value) {
    if (value == null) {
      return null;
    }
    return value.replace("\r", "").replace("\n", "").replace("\0", "").trim();
  }

  /**
   * Parse a Structured Header List according to RFC 9651 Section 3.1.
   *
   * @param headerValue The list header value string
   * @return Ordered {@link java.util.List} of member item strings
   */
  public static java.util.List<String> parseList(String headerValue) {
    java.util.List<String> list = new java.util.ArrayList<>();
    if (headerValue == null || headerValue.isBlank()) {
      return list;
    }
    java.util.List<String> members = splitMembers(headerValue);
    for (String member : members) {
      list.add(sanitizeHeaderValue(unquoteString(member)));
    }
    return list;
  }

  /**
   * Split a comma-separated structured header value into members per RFC 9651 Section 3, respecting
   * double-quoted strings so that commas inside quotes are not treated as member separators.
   *
   * @param headerValue The raw header string
   * @return List of trimmed member strings
   */
  private static java.util.List<String> splitMembers(String headerValue) {
    java.util.List<String> members = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    boolean escaped = false;

    for (int i = 0; i < headerValue.length(); i++) {
      char c = headerValue.charAt(i);
      if (escaped) {
        current.append(c);
        escaped = false;
      } else if (c == '\\') {
        current.append(c);
        escaped = true;
      } else if (c == '"') {
        inQuotes = !inQuotes;
        current.append(c);
      } else if (c == ',' && !inQuotes) {
        String member = current.toString().trim();
        if (!member.isEmpty()) {
          members.add(member);
        }
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    String lastMember = current.toString().trim();
    if (!lastMember.isEmpty()) {
      members.add(lastMember);
    }
    return members;
  }

  /**
   * Unquotes an RFC 9651 String item by removing enclosing DQUOTEs and unescaping escaped
   * characters (\" and \\).
   */
  private static String unquoteString(String valStr) {
    if (valStr != null
        && valStr.length() >= 2
        && valStr.startsWith("\"")
        && valStr.endsWith("\"")) {
      String inner = valStr.substring(1, valStr.length() - 1);
      return inner.replace("\\\"", "\"").replace("\\\\", "\\");
    }
    return valStr;
  }
}
