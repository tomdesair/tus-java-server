package me.desair.tus.server.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transparently coalesces chunks of a HTTP stream that uses Transfer-Encoding chunked. This {@link
 * InputStream} wrapper also supports collecting Trailer header values that are sent at the end of
 * the stream. <br>
 * Based on org.apache.commons.httpclient.ChunkedInputStream
 */
public class HttpChunkedEncodingInputStream extends InputStream {

  private static final Logger log = LoggerFactory.getLogger(HttpChunkedEncodingInputStream.class);

  /** The input stream that we're wrapping. */
  private InputStream in;

  /** The current chunk size. */
  private int chunkSize = 0;

  /** The current position within the current chunk. */
  private int pos = 0;

  /** True if we'are at the beginning of stream. */
  private boolean bof = true;

  /** True if we've reached the end of stream. */
  private boolean eof = false;

  /** True if this stream is closed. */
  private boolean closed = false;

  /** Map to store any trailer headers. */
  private Map<String, List<String>> trailerHeaders = null;

  /**
   * Wrap the given input stream and store any trailing headers in the provided map.
   *
   * @param in the raw input stream
   * @param trailerHeaders Map to store any trailer header values. Can be <b>null</b>.
   */
  public HttpChunkedEncodingInputStream(InputStream in, Map<String, List<String>> trailerHeaders) {

    if (in == null) {
      throw new IllegalArgumentException("InputStream parameter may not be null");
    }
    this.in = in;
    this.trailerHeaders = trailerHeaders;
  }

  /**
   * Wrap the given input stream. Do not store any trailing headers.
   *
   * @param in the raw input stream
   */
  public HttpChunkedEncodingInputStream(InputStream in) {
    this(in, null);
  }

  /**
   * Reads the next byte of data from the input stream. The value byte is returned as an <code>int
   * </code> in the range <code>0</code> to <code>255</code>.
   *
   * @return -1 of the end of the stream has been reached or the next data byte
   * @throws IOException If an IO problem occurs
   */
  @Override
  public int read() throws IOException {

    if (closed) {
      throw new IOException("Attempted read from closed stream.");
    }
    if (eof) {
      return -1;
    }
    if (pos >= chunkSize) {
      nextChunk();
      if (eof) {
        return -1;
      }
    }
    pos++;
    return in.read();
  }

  /**
   * Read some bytes from the stream.
   *
   * @param b The byte array that will hold the contents from the stream.
   * @param off The offset into the byte array at which bytes will start to be placed.
   * @param len the maximum number of bytes that can be returned.
   * @return The number of bytes returned or -1 if the end of stream has been reached.
   * @see java.io.InputStream#read(byte[], int, int)
   * @throws IOException if an IO problem occurs.
   */
  @Override
  public int read(byte[] b, int off, int len) throws IOException {

    if (closed) {
      throw new IOException("Attempted read from closed stream.");
    }

    if (eof) {
      return -1;
    }
    if (pos >= chunkSize) {
      nextChunk();
      if (eof) {
        return -1;
      }
    }
    int minLen = Math.min(len, chunkSize - pos);
    int count = in.read(b, off, minLen);
    pos += count;
    return count;
  }

  /**
   * Read some bytes from the stream.
   *
   * @param b The byte array that will hold the contents from the stream.
   * @return The number of bytes returned or -1 if the end of stream has been reached.
   * @see java.io.InputStream#read(byte[])
   * @throws IOException if an IO problem occurs.
   */
  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  /**
   * Read the CRLF terminator.
   *
   * @throws IOException If an IO error occurs.
   */
  private void readCrLf() throws IOException {
    int cr = in.read();
    int lf = in.read();
    if ((cr != '\r') || (lf != '\n')) {
      throw new IOException("CRLF expected at end of chunk: " + cr + "/" + lf);
    }
  }

  /**
   * Read the next chunk.
   *
   * @throws IOException If an IO error occurs.
   */
  private void nextChunk() throws IOException {
    if (!bof) {
      readCrLf();
    }
    chunkSize = getChunkSize();
    if (chunkSize < 0) {
      throw new IOException("Negative chunk size");
    }

    bof = false;
    pos = 0;
    if (chunkSize == 0) {
      eof = true;
      parseTrailerHeaders();
    }
  }

  /**
   * Expects the stream to start with a chunk size in hex with optional comments after a semicolon.
   * The line must end with a CRLF: "a3; some comment\r\n" Positions the stream at the start of the
   * next line.
   *
   * @return the chunk size as integer
   * @throws IOException when the chunk size could not be parsed
   */
  private int getChunkSize() throws IOException {

    String dataString = readChunkSizeInformation();

    int separator = dataString.indexOf(';');
    dataString = (separator > 0) ? dataString.substring(0, separator).trim() : dataString.trim();

    int result;
    try {
      result = Integer.parseInt(dataString.trim(), 16);
    } catch (NumberFormatException e) {
      throw new IOException("Bad chunk size: " + dataString);
    }
    return result;
  }

  private String readChunkSizeInformation() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    ChunkSizeState state = ChunkSizeState.NORMAL;
    while (state != ChunkSizeState.END) {
      int b = in.read();
      if (b == -1) {
        throw new IOException("Chunked stream ended unexpectedly");
      }
      state = state.process(in, baos, b);
    }

    // parse data
    return new String(baos.toByteArray(), StandardCharsets.US_ASCII);
  }

  /**
   * Reads and stores the Trailer headers.
   *
   * @throws IOException If an IO problem occurs
   */
  private void parseTrailerHeaders() throws IOException {
    if (trailerHeaders != null) {
      List<Pair<String, String>> footers = parseHeaders(in, StandardCharsets.US_ASCII);
      for (Pair<String, String> footer : footers) {
        List<String> values = trailerHeaders.get(footer.getKey());
        if (values == null) {
          values = new LinkedList<>();
          trailerHeaders.put(footer.getKey(), values);
        }

        values.add(footer.getValue());
      }
    }
  }

  /**
   * Upon close, this reads the remainder of the chunked message, leaving the underlying socket at a
   * position to start reading the next response without scanning.
   *
   * @throws IOException If an IO problem occurs.
   */
  @Override
  public void close() throws IOException {
    if (!closed) {
      try {
        if (!eof) {
          exhaustInputStream();
        }
      } finally {
        eof = true;
        closed = true;
      }
    }
  }

  /**
   * Exhaust our input stream, reading until EOF has been encountered.
   *
   * <p>Note that this function is intended as a non-public utility. This is a little weird, but it
   * seemed silly to make a utility class for this one function, so instead it is just static and
   * shared that way.
   *
   * @throws IOException If an IO problem occurs
   */
  private void exhaustInputStream() throws IOException {
    // read and discard the remainder of the message
    byte[] buffer = new byte[1024];

    log.trace("Clearing underlying input stream, this is what was left:");
    while (in.read(buffer) >= 0) {
      if (log.isTraceEnabled()) {
        log.trace(new String(buffer, StandardCharsets.UTF_8));
      }
    }
  }

  private List<Pair<String, String>> parseHeaders(InputStream is, Charset charset)
      throws IOException {
    List<Pair<String, String>> headers = new LinkedList<>();
    String name = null;
    StringBuilder value = null;
    String line = readLine(is, charset);
    while (org.apache.commons.lang3.StringUtils.isNotBlank(line)) {
      // Parse the header name and value
      // Check for folded headers first
      // Detect LWS-char see HTTP/1.0 or HTTP/1.1 Section 2.2
      // discussion on folded headers
      if (isLwsChar(line.charAt(0))) {
        // we have continuation folded header
        // so append value
        if (value != null) {
          value.append(' ');
          value.append(line.trim());
        }
      } else {
        // make sure we save the previous name, value pair if present
        addHeaderValue(headers, name, value);

        // Otherwise we should have normal HTTP header line
        // Parse the header name and value
        int colon = line.indexOf(':');
        if (colon >= 0) {
          name = line.substring(0, colon).trim();
          value = new StringBuilder(line.substring(colon + 1).trim());
        }
      }

      line = readLine(is, charset);
    }

    // make sure we save the last name,value pair if present
    addHeaderValue(headers, name, value);

    return headers;
  }

  private void addHeaderValue(
      List<Pair<String, String>> headers, String name, StringBuilder value) {
    if (name != null) {
      headers.add(Pair.of(name, value.toString()));
    }
  }

  private boolean isLwsChar(char c) {
    return c == ' ' || c == '\t';
  }

  private String readLine(InputStream inputStream, Charset charset) throws IOException {
    byte[] rawdata = readRawLine(inputStream);
    if (rawdata == null || rawdata.length == 0) {
      return null;
    }
    // strip CR and LF from the end
    int len = rawdata.length;
    int offset = 0;
    if (rawdata[len - 1] == '\n') {
      offset++;
      if (len > 1 && rawdata[len - 2] == '\r') {
        offset++;
      }
    }

    return new String(rawdata, 0, len - offset, charset);
  }

  private byte[] readRawLine(InputStream inputStream) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int ch;
    while ((ch = inputStream.read()) >= 0) {
      buf.write(ch);
      if (ch == '\n') { // be tolerant (RFC-2616 Section 19.3)
        break;
      }
    }
    return buf.toByteArray();
  }

  private enum ChunkSizeState {
    NORMAL {
      @Override
      public ChunkSizeState process(InputStream in, ByteArrayOutputStream baos, int b)
          throws IOException {

        ChunkSizeState newState;
        if (b == '\r') {
          newState = READ_CARRIAGE_RETURN;
        } else {
          if (b == '\"') {
            newState = INSIDE_QUOTED_STRING;
          } else {
            newState = NORMAL;
          }
          baos.write(b);
        }
        return newState;
      }
    },
    READ_CARRIAGE_RETURN {
      @Override
      public ChunkSizeState process(InputStream in, ByteArrayOutputStream baos, int b)
          throws IOException {

        if (b != '\n') {
          // this was not CRLF
          throw new IOException(
              "Protocol violation: Unexpected" + " single newline character in chunk size");
        }
        return END;
      }
    },
    INSIDE_QUOTED_STRING {
      @Override
      public ChunkSizeState process(InputStream in, ByteArrayOutputStream baos, int b)
          throws IOException {

        ChunkSizeState newState;
        if (b == '\\') {
          int nextByte = in.read();
          if (nextByte >= 0) {
            baos.write(nextByte);
          }
          newState = INSIDE_QUOTED_STRING;
        } else {
          if (b == '\"') {
            newState = NORMAL;
          } else {
            newState = INSIDE_QUOTED_STRING;
          }
          baos.write(b);
        }
        return newState;
      }
    },
    END {
      @Override
      public ChunkSizeState process(InputStream in, ByteArrayOutputStream baos, int b)
          throws IOException {
        throw new UnsupportedOperationException("The END state cannot do any processing");
      }
    };

    public abstract ChunkSizeState process(InputStream in, ByteArrayOutputStream baos, int b)
        throws IOException;
  }
}
