package me.desair.tus.server;

import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

/**
 * Class that represents a HTTP method.
 * The X-HTTP-Method-Override request header MUST be a string which MUST be interpreted as the request’s
 * method by the Server, if the header is presented. The actual method of the request MUST be ignored.
 * The Client SHOULD use this header if its environment does not support the PATCH or DELETE methods.
 * (https://tus.io/protocols/resumable-upload.html#x-http-method-override)
 */
public enum HttpMethod {

    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    CONNECT,
    OPTIONS,
    TRACE,
    PATCH;

    public static HttpMethod forName(final String name) {
        for (HttpMethod method : HttpMethod.values()) {
            if (StringUtils.equalsIgnoreCase(method.name(), name)) {
                return method;
            }
        }

        return null;
    }

    public static HttpMethod getMethodIfSupported(final HttpServletRequest request,
                                                  Set<HttpMethod> supportedHttpMethods) {
        Validate.notNull(request, "The HttpServletRequest cannot be null");

        String requestMethod = request.getHeader(HttpHeader.METHOD_OVERRIDE);
        if (StringUtils.isBlank(requestMethod) || forName(requestMethod) == null) {
            requestMethod = request.getMethod();
        }

        HttpMethod httpMethod = forName(requestMethod);
        return httpMethod != null && supportedHttpMethods.contains(httpMethod) ? httpMethod : null;
    }

}
