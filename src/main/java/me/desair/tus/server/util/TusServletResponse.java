package me.desair.tus.server.util;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.commons.lang3.StringUtils;

/**
 * {@link HttpServletResponseWrapper} to capture header values set on the current {@link HttpServletResponse}
 */
public class TusServletResponse extends HttpServletResponseWrapper {

    private final Map<String, List<String>> headers = new HashMap<>();

    /**
     * Constructs a response adaptor wrapping the given response.
     *
     * @param response The response that has to be wrapped
     * @throws IllegalArgumentException if the response is null
     */
    public TusServletResponse(final HttpServletResponse response) {
        super(response);
    }

    @Override
    public void setDateHeader(final String name, final long date) {
        super.setDateHeader(name, date);
        overwriteHeader(name, Objects.toString(date));
    }

    @Override
    public void addDateHeader(final String name, final long date) {
        super.addDateHeader(name, date);
        recordHeader(name, Objects.toString(date));
    }

    @Override
    public void setHeader(final String name, final String value) {
        super.setHeader(name, value);
        overwriteHeader(name, value);
    }

    @Override
    public void addHeader(final String name, final String value) {
        super.addHeader(name, value);
        recordHeader(name, value);
    }

    @Override
    public void setIntHeader(final String name, final int value) {
        super.setIntHeader(name, value);
        overwriteHeader(name, Objects.toString(value));
    }

    @Override
    public void addIntHeader(final String name, final int value) {
        super.addIntHeader(name, value);
        recordHeader(name, Objects.toString(value));
    }

    @Override
    public String getHeader(final String name) {
        String value;
        if (headers.containsKey(name)) {
            value = headers.get(name).get(0);
        } else {
            value = super.getHeader(name);
        }
        return StringUtils.trimToNull(value);
    }

    private void recordHeader(final String name, final String value) {
        List<String> values = headers.get(name);
        if (values == null) {
            values = new LinkedList<>();
            headers.put(name, values);
        }

        values.add(value);
    }

    private void overwriteHeader(final String name, final String value) {
        List<String> values = new LinkedList<>();
        values.add(value);
        headers.put(name, values);
    }

}
