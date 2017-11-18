package me.desair.tus.server.util;

import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;

public class Utils {

    public static String getHeader(final HttpServletRequest request, final String header) {
        return StringUtils.trimToEmpty(request.getHeader(header));
    }

    public static Long getLongHeader(final HttpServletRequest request, final String header) {
        try {
            return Long.valueOf(getHeader(request, header));
        } catch(NumberFormatException ex) {
            return null;
        }
    }
}
