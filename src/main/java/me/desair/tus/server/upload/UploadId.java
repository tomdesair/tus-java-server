package me.desair.tus.server.upload;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Objects;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unique identifier of an upload process in the tus protocol
 * TODO UNIT TEST
 */
public class UploadId implements Serializable {

    private static final String UPLOAD_ID_CHARSET = "UTF-8";
    private static final Logger log = LoggerFactory.getLogger(UploadId.class);

    private String urlSafeValue;
    private String originalValue;

    /**
     * Create a new {@link UploadId} instance based on the provided value.
     * @param inputValue The value to use for constructing the ID
     */
    public UploadId(String inputValue) {
        Validate.notBlank(inputValue, "The upload ID value cannot be blank");
        this.originalValue = inputValue;

        URLCodec codec = new URLCodec();
        //Check if value is not encoded already
        try {
            if (inputValue.equals(codec.decode(inputValue, UPLOAD_ID_CHARSET))) {
                this.urlSafeValue = codec.encode(inputValue, "UTF-8");
            } else {
                //value is already encoded, use as is
                this.urlSafeValue = inputValue;
            }
        } catch (DecoderException | UnsupportedEncodingException e) {
            log.warn("Unable to URL encode upload ID value", e);
            this.urlSafeValue = inputValue;
        }
    }

    /**
     * The original input value that was provided when constructing this upload ID
     * @return The original value
     */
    public String getOriginalValue() {
        return this.originalValue;
    }

    @Override
    public String toString() {
        return urlSafeValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UploadId)) {
            return false;
        }

        UploadId uploadId = (UploadId) o;
        return Objects.equals(urlSafeValue, uploadId.urlSafeValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(urlSafeValue);
    }
}
