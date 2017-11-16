package me.desair.tus.server;

import me.desair.tus.server.file.FileStoreService;
import org.apache.commons.lang3.Validate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TusFileUploadHandler {

    private HttpServletRequest servletRequest;
    private HttpServletResponse servletResponse;
    private FileStoreService fileStoreService;

    public TusFileUploadHandler(final HttpServletRequest servletRequest, final HttpServletResponse servletResponse) {
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
    }

    public TusFileUploadHandler withFileStoreService(final FileStoreService fileStoreService) {
        Validate.notNull(fileStoreService, "The FileStoreService cannot be null");
        this.fileStoreService = fileStoreService;

        return this;
    }

    public void process() {
        //TODO process tus upload
    }
}
