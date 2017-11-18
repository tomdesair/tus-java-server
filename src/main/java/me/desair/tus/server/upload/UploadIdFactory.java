package me.desair.tus.server.upload;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

public class UploadIdFactory {

    private String contextPath = "/";

    public void setContextPath(final String contextPath) {
        Validate.notNull(contextPath, "The context path cannot be null");
        this.contextPath = contextPath;
    }

    public UUID readUploadId(final HttpServletRequest request) {
        String pathId = StringUtils.substringAfter(request.getRequestURI(), contextPath);
        UUID id = null;

        if(StringUtils.isNotBlank(pathId)) {
            try {
                id = UUID.fromString(pathId);
            } catch (IllegalArgumentException ex) {
                id = null;
            }
        }

        return id;
    }

}
