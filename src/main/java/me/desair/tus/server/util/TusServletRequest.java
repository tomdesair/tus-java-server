package me.desair.tus.server.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.lang3.StringUtils;

public class TusServletRequest extends HttpServletRequestWrapper {

    private CountingInputStream countingInputStream;
    private DigestInputStream digestInputStream;
    private Map<String, List<String>> trailerHeaders = new HashMap<>();
    private InputStream contentInputStream = null;

    /**
     * Constructs a request object wrapping the given request.
     *
     * @param request
     * @throws IllegalArgumentException if the request is null
     */
    public TusServletRequest(final HttpServletRequest request) {
        super(request);
    }

    public InputStream getContentInputStream() throws IOException {
        if(contentInputStream == null) {
            InputStream originalInputStream = super.getInputStream();

            countingInputStream = new CountingInputStream(originalInputStream);
            contentInputStream = countingInputStream;

            ChecksumAlgorithm checksumAlgorithm = ChecksumAlgorithm.forUploadChecksumHeader(getHeader(HttpHeader.UPLOAD_CHECKSUM));
            if (checksumAlgorithm != null) {
                digestInputStream = new DigestInputStream(contentInputStream, checksumAlgorithm.getMessageDigest());
                contentInputStream = digestInputStream;
            }

            if(StringUtils.equalsIgnoreCase("chunked", getHeader(HttpHeader.TRANSFER_ENCODING))) {
                contentInputStream = new ChunkedInputStream(contentInputStream, trailerHeaders);
            }
        }

        return contentInputStream;
    }

    public long getBytesRead() {
        return countingInputStream.getByteCount();
    }

    public boolean hasChecksum() {
        return digestInputStream != null;
    }

    public String getChecksum() {
        return digestInputStream == null ? null :
                Base64.encodeBase64String(digestInputStream.getMessageDigest().digest());
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);

        if(StringUtils.isBlank(value) && trailerHeaders.containsKey(name)) {
            value = trailerHeaders.get(name).get(0);
        }

        return value;
    }
}
