package me.desair.tus.server.util;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link HttpServletResponseWrapper} to capture header values set on the current {@link
 * HttpServletResponse}.
 */
public class TusServletResponse extends HttpServletResponseWrapper {

  private Map<String, List<String>> headers = new HashMap<>();

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
    super.setDateHeader(name, date);
    overwriteHeader(name, Objects.toString(date));
  }

  @Override
  public void addDateHeader(String name, long date) {
    super.addDateHeader(name, date);
    recordHeader(name, Objects.toString(date));
  }

  @Override
  public void setHeader(String name, String value) {
    super.setHeader(name, value);
    overwriteHeader(name, value);
  }

  @Override
  public void addHeader(String name, String value) {
    super.addHeader(name, value);
    recordHeader(name, value);
  }

  @Override
  public void setIntHeader(String name, int value) {
    super.setIntHeader(name, value);
    overwriteHeader(name, Objects.toString(value));
  }

  @Override
  public void addIntHeader(String name, int value) {
    super.addIntHeader(name, value);
    recordHeader(name, Objects.toString(value));
  }

  @Override
  public String getHeader(String name) {
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
    List<String> values = new LinkedList<>();
    values.add(value);
    headers.put(name, values);
  }
}
