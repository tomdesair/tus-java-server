package me.desair.tus.server.upload;

import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Common abstract super class to implement service that use the disk file system
 */
public class AbstractDiskBasedService {

    private Path storagePath;

    public AbstractDiskBasedService(final String storagePath) {
        Validate.notBlank(storagePath, "The storage path cannot be blank");
        this.storagePath = Paths.get(storagePath);
    }

    protected Path getPathInStorageDirectory(final UUID id) {
        if(id == null) {
            return null;
        } else {
            return storagePath.resolve(id.toString());
        }
    }
}
