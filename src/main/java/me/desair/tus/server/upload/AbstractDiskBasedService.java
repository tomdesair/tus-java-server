package me.desair.tus.server.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import me.desair.tus.server.TusFileUploadService;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common abstract super class to implement service that use the disk file system
 */
public class AbstractDiskBasedService {

    private static final Logger log = LoggerFactory.getLogger(TusFileUploadService.class);

    private Path storagePath;

    public AbstractDiskBasedService(final String path) {
        Validate.notBlank(path, "The storage path cannot be blank");
        this.storagePath = Paths.get(path);

        if(!Files.exists(storagePath)) {
            try {
                Files.createDirectories(storagePath);
            } catch (IOException e) {
                String message = "Unable to create the directory specified by the storage path " + path;
                log.error(message, e);
                throw new RuntimeException(message, e);
            }
        }
    }

    protected Path getPathInStorageDirectory(final UUID id) {
        if(id == null) {
            return null;
        } else {
            return storagePath.resolve(id.toString());
        }
    }
}
