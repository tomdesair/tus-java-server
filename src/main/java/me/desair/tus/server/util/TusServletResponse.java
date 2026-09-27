package me.desair.tus.server.util;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link HttpServletResponseWrapper} to capture header values set on the current {@link
 * HttpServletResponse}.
 */
public class TusServletResponse extends HttpServletResponseWrapper {

  // HTTP headers are case-insensitive per RFC 9110 §5.1. Using a TreeMap with
  // String.CASE_INSENSITIVE_ORDER ensures getHeader("location") finds setHeader("Location").
  private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  /**
   * Constructs a response adaptor wrapping the given response.
   *
   * @param response The response that has to be wrapped
   * @throws IllegalArgumentException if the response is null
   */
  public TusServletResponse(HttpServletResponse response) {
    super(response);
  }

  @Override
  public void setDateHeader(String name, long date) {
    if (name == null) {
      return;
    }
    super.setDateHeader(name, date);
    overwriteHeader(name, Objects.toString(date));
  }

  @Override
  public void addDateHeader(String name, long date) {
    if (name == null) {
      return;
    }
    super.addDateHeader(name, date);
    recordHeader(name, Objects.toString(date));
  }

  @Override
  public void setHeader(String name, String value) {
    if (name == null) {
      return;
    }
    String sanitizedValue = sanitizeHeaderValue(value);
    super.setHeader(name, sanitizedValue);
    overwriteHeader(name, sanitizedValue);
  }

  @Override
  public void addHeader(String name, String value) {
    if (name == null) {
      return;
    }
    String sanitizedValue = sanitizeHeaderValue(value);
    super.addHeader(name, sanitizedValue);
    recordHeader(name, sanitizedValue);
  }

  @Override
  public void setIntHeader(String name, int value) {
    if (name == null) {
      return;
    }
    super.setIntHeader(name, value);
    overwriteHeader(name, Objects.toString(value));
  }

  @Override
  public void addIntHeader(String name, int value) {
    if (name == null) {
      return;
    }
    super.addIntHeader(name, value);
    recordHeader(name, Objects.toString(value));
  }

  @Override
  public String getHeader(String name) {
    if (name == null) {
      return null;
    }
    String value;
    if (headers.containsKey(name)) {
      value = headers.get(name).get(0);
    } else {
      value = super.getHeader(name);
    }
    return StringUtils.trimToNull(value);
  }

  private void recordHeader(String name, String value) {
    List<String> values = headers.computeIfAbsent(name, k -> new LinkedList<>());
    values.add(value);
  }

  private void overwriteHeader(String name, String value) {
    if (value == null) {
      headers.remove(name);
    } else {
      List<String> values = new LinkedList<>();
      values.add(value);
      headers.put(name, values);
    }
  }

  private String sanitizeHeaderValue(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("[\r\n]", "");
  }
}
