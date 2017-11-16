package me.desair.tus.server;

import lombok.NonNull;
import me.desair.tus.server.file.FileStorageService;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Class that represents a HTTP method.
 * The X-HTTP-Method-Override request header MUST be a string which MUST be interpreted as the request’s
 * method by the Server, if the header is presented. The actual method of the request MUST be ignored.
 * The Client SHOULD use this header if its environment does not support the PATCH or DELETE methods.
 * (https://tus.io/protocols/resumable-upload.html#x-http-method-override)
 *
 * TODO TOM UNIT TEST
 */
public enum HttpMethod {

    DELETE {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    },
    GET {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    },
    HEAD {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    },
    PATCH {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    },
    POST {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    },
    PUT {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    },
    OPTIONS {
        @Override
        public void process(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FileStorageService fileStorageService) {
            //TODO TOM DELEGATE
        }
    };

    public static HttpMethod forName(final String name) {
        for (HttpMethod method : HttpMethod.values()) {
            if (StringUtils.equalsIgnoreCase(method.name(), name)) {
                return method;
            }
        }

        return null;
    }

    public static HttpMethod getMethod(@NonNull final HttpServletRequest request) {
        String requestMethod = request.getHeader(HttpHeader.METHOD_OVERRIDE);
        if (StringUtils.isBlank(requestMethod) || forName(requestMethod) == null) {
            requestMethod = request.getMethod();
        }

        return forName(requestMethod);
    }

    public abstract void process(final HttpServletRequest servletRequest,
                        final HttpServletResponse servletResponse,
                        final FileStorageService fileStorageService);
}
