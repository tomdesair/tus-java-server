package me.desair.tus.server.util;

import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.*;

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

    public static <T> T readSerializable(final Path path, final Class<T> clazz) throws IOException {
        T info = null;
        if (path != null) {
            FileLock lock = null;
            try (FileChannel channel = FileChannel.open(path, READ)) {
                lock = channel.lock(0L, Long.MAX_VALUE, true);

                try(ObjectInputStream ois = new ObjectInputStream(Channels.newInputStream(channel))) {
                    info = clazz.cast(ois.readObject());
                } catch (ClassNotFoundException e) {
                    //This should not happen
                    info = null;
                }

            } finally {
                if (lock != null) {
                    lock.release();
                }
            }
        }
        return info;
    }


    public static void writeSerializable(final Serializable object, final Path path) throws IOException {
        if (path != null) {
            FileLock lock = null;
            try (FileChannel channel = FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING)) {
                lock = channel.lock();

                try(OutputStream buffer = new BufferedOutputStream(Channels.newOutputStream(channel));
                    ObjectOutput output = new ObjectOutputStream(buffer)) {

                    output.writeObject(object);
                }

            } finally {
                if (lock != null) {
                    lock.release();
                }
            }
        }
    }
}
