package me.desair.tus.server.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.TusFeature;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.lang3.StringUtils;

public class TusServletRequest extends HttpServletRequestWrapper {

    private CountingInputStream countingInputStream;
    private Map<ChecksumAlgorithm, DigestInputStream> digestInputStreamMap = new EnumMap<>(ChecksumAlgorithm.class);
    private DigestInputStream singleDigestInputStream = null;
    private InputStream contentInputStream = null;

    private Map<String, List<String>> trailerHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> processedBySet = new TreeSet<>();

    /**
     * Constructs a request object wrapping the given request.
     *
     * @param request The upload request we need to wrap
     * @throws IllegalArgumentException if the request is null
     */
    public TusServletRequest(final HttpServletRequest request) {
        super(request);
    }

    public InputStream getContentInputStream() throws IOException {
        if(contentInputStream == null) {
            contentInputStream = super.getInputStream();

            //If we're dealing with chunked transfer encoding, abstract it so that the rest of our code doesn't need to care
            boolean isChunked = hasChunkedTransferEncoding();
            if(isChunked) {
                contentInputStream = new HttpChunkedEncodingInputStream(contentInputStream, trailerHeaders);
            }

            countingInputStream = new CountingInputStream(contentInputStream);
            contentInputStream = countingInputStream;

            ChecksumAlgorithm checksumAlgorithm = ChecksumAlgorithm.forUploadChecksumHeader(getHeader(HttpHeader.UPLOAD_CHECKSUM));
            if(isChunked) {
                //Since the Checksum header can still come at the end, keep track of all checksums
                for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
                    DigestInputStream is = new DigestInputStream(contentInputStream, algorithm.getMessageDigest());
                    digestInputStreamMap.put(algorithm, is);

                    contentInputStream = is;
                }
            } else if (checksumAlgorithm != null) {
                singleDigestInputStream = new DigestInputStream(contentInputStream, checksumAlgorithm.getMessageDigest());
                contentInputStream = singleDigestInputStream;
            }

        }

        return contentInputStream;
    }

    private boolean hasChunkedTransferEncoding() {
        return StringUtils.equalsIgnoreCase("chunked", getHeader(HttpHeader.TRANSFER_ENCODING));
    }

    public long getBytesRead() {
        return countingInputStream == null ? 0 : countingInputStream.getByteCount();
    }

    public boolean hasCalculatedChecksum() {
        return singleDigestInputStream != null || !digestInputStreamMap.isEmpty();
    }

    public String getCalculatedChecksum(ChecksumAlgorithm algorithm) {
        MessageDigest messageDigest = getMessageDigest(algorithm);
        return messageDigest == null ? null :
                Base64.encodeBase64String(messageDigest.digest());
    }

    private MessageDigest getMessageDigest(final ChecksumAlgorithm algorithm) {
        if(digestInputStreamMap.containsKey(algorithm)) {
            return digestInputStreamMap.get(algorithm).getMessageDigest();
        } else if(singleDigestInputStream != null) {
            return singleDigestInputStream.getMessageDigest();
        } else {
            return null;
        }
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);

        if(StringUtils.isBlank(value) && trailerHeaders.containsKey(name)) {
            List<String> values = trailerHeaders.get(name);
            if(values != null && !values.isEmpty()) {
                value = values.get(0);
            }
        }

        return value;
    }

    public boolean isProcessedBy(TusFeature processor) {
        return processedBySet.contains(processor.getName());
    }

    public void addProcessor(TusFeature processor) {
        processedBySet.add(processor.getName());
    }
}
