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
import java.nio.file.Path;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

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
            try (FileChannel channel = FileChannel.open(path, READ)) {
                //Lock will be released when the channel is closed
                channel.lock(0L, Long.MAX_VALUE, true);

                try(ObjectInputStream ois = new ObjectInputStream(Channels.newInputStream(channel))) {
                    info = clazz.cast(ois.readObject());
                } catch (ClassNotFoundException e) {
                    //This should not happen
                    info = null;
                }
            }
        }
        return info;
    }


    public static void writeSerializable(final Serializable object, final Path path) throws IOException {
        if (path != null) {
            try (FileChannel channel = FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING)) {
                //Lock will be released when the channel is closed
                channel.lock();

                try(OutputStream buffer = new BufferedOutputStream(Channels.newOutputStream(channel));
                    ObjectOutput output = new ObjectOutputStream(buffer)) {

                    output.writeObject(object);
                }
            }
        }
    }
}
