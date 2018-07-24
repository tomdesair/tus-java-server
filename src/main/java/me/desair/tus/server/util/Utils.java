package me.desair.tus.server.util;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that contains various static helper methods
 */
public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);
    private static final int LOCK_FILE_RETRY_COUNT = 3;
    private static final long LOCK_FILE_SLEEP_TIME = 500;

    private Utils() {
        //This is a utility class that only holds static utility methods
    }

    public static String getHeader(final HttpServletRequest request, final String header) {
        return StringUtils.trimToEmpty(request.getHeader(header));
    }

    public static Long getLongHeader(final HttpServletRequest request, final String header) {
        try {
            return Long.valueOf(getHeader(request, header));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static List<String> parseConcatenationIDsFromHeader(String uploadConcatValue) {
        List<String> output = new LinkedList<>();

        String idString = StringUtils.substringAfter(uploadConcatValue, ";");
        for (String id : StringUtils.trimToEmpty(idString).split("\\s")) {
            output.add(id);
        }

        return output;
    }

    public static <T> T readSerializable(final Path path, final Class<T> clazz) throws IOException {
        T info = null;
        if (path != null) {
            try (FileChannel channel = FileChannel.open(path, READ)) {
                //Lock will be released when the channel is closed
                if (lockFileShared(channel) != null) {

                    try (ObjectInputStream ois = new ObjectInputStream(Channels.newInputStream(channel))) {
                        info = clazz.cast(ois.readObject());
                    } catch (ClassNotFoundException e) {
                        //This should not happen
                        info = null;
                    }
                } else {
                    throw new IOException("Unable to lock file " + path);
                }
            }
        }
        return info;
    }


    public static void writeSerializable(final Serializable object, final Path path) throws IOException {
        if (path != null) {
            try (FileChannel channel = FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING)) {
                //Lock will be released when the channel is closed
                if (lockFileExclusively(channel) != null) {

                    try (OutputStream buffer = new BufferedOutputStream(Channels.newOutputStream(channel));
                         ObjectOutput output = new ObjectOutputStream(buffer)) {

                        output.writeObject(object);
                    }
                } else {
                    throw new IOException("Unable to lock file " + path);
                }
            }
        }
    }

    public static FileLock lockFileExclusively(FileChannel channel) throws IOException {
        return lockFile(channel, false);
    }

    public static FileLock lockFileShared(FileChannel channel) throws IOException {
        return lockFile(channel, true);
    }

    public static void sleep(long sleepTime) {
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.warn("Sleep was interrupted");
            // Restore interrupted state...
            Thread.currentThread().interrupt();
        }
    }

    private static FileLock lockFile(FileChannel channel, boolean shared) throws IOException {
        int i = 0;
        FileLock lock = null;
        do {
            if (i > 0) {
                sleep(LOCK_FILE_SLEEP_TIME);
            }

            lock = channel.tryLock(0L, Long.MAX_VALUE, shared);

            i++;
        } while (lock == null && i < LOCK_FILE_RETRY_COUNT);

        return lock;
    }
}
