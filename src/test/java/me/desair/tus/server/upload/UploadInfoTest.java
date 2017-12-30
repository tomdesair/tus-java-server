package me.desair.tus.server.upload;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.*;

public class UploadInfoTest {

    @Test
    public void hasMetadata() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setEncodedMetadata("Encoded Metadata");
        assertTrue(info.hasMetadata());
    }

    @Test
    public void hasMetadataFalse() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setEncodedMetadata(null);
        assertFalse(info.hasMetadata());
    }

    @Test
    public void testGetMetadataMultipleValues() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setEncodedMetadata(
                "filename d29ybGRfZG9taW5hdGlvbiBwbGFuLnBkZg==," +
                "filesize MTEya2I=, " +
                "mimetype YXBwbGljYXRpb24vcGRm , " +
                "user 546L5LqU     ,    " +
                "user VG9tIERlc2Fpcg==");

        assertThat(info.getMetadata(), containsInAnyOrder(
                Pair.of("filename", "world_domination plan.pdf"),
                Pair.of("filesize", "112kb"),
                Pair.of("mimetype", "application/pdf"),
                Pair.of("user", "王五"),
                Pair.of("user", "Tom Desair"))
        );
    }

    @Test
    public void testGetMetadataSingleValues() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setEncodedMetadata("filename d29ybGRfZG9taW5hdGlvbl9wbGFuLnBkZg==");

        assertThat(info.getMetadata(), contains(
                Pair.of("filename", "world_domination_plan.pdf"))
        );
    }

    @Test
    public void testGetMetadataNull() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setEncodedMetadata(null);
        assertTrue(info.getMetadata().isEmpty());
    }

    @Test
    public void hasLength() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(10L);
        assertTrue(info.hasLength());
    }

    @Test
    public void hasLengthFalse() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(null);
        assertFalse(info.hasLength());
    }

    @Test
    public void isUploadInProgressNoLengthNoOffset() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(null);
        info.setOffset(null);
        assertTrue(info.isUploadInProgress());
    }

    @Test
    public void isUploadInProgressNoLengthWithOffset() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(null);
        info.setOffset(10L);
        assertTrue(info.isUploadInProgress());
    }

    @Test
    public void isUploadInProgressOffsetDoesNotMatchLength() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(10L);
        info.setOffset(8L);
        assertTrue(info.isUploadInProgress());
    }

    @Test
    public void isUploadInProgressOffsetMatchesLength() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(10L);
        info.setOffset(10L);
        assertFalse(info.isUploadInProgress());
    }

    @Test
    public void testEquals() throws Exception {
        UploadInfo info1 = new UploadInfo();
        info1.setLength(10L);
        info1.setOffset(5L);
        info1.setEncodedMetadata("Encoded-Metadata");
        info1.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        UploadInfo info2 = new UploadInfo();
        info2.setLength(10L);
        info2.setOffset(5L);
        info2.setEncodedMetadata("Encoded-Metadata");
        info2.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        UploadInfo info3 = new UploadInfo();
        info3.setLength(9L);
        info3.setOffset(5L);
        info3.setEncodedMetadata("Encoded-Metadata");
        info3.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        UploadInfo info4 = new UploadInfo();
        info4.setLength(10L);
        info4.setOffset(6L);
        info4.setEncodedMetadata("Encoded-Metadata");
        info4.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        UploadInfo info5 = new UploadInfo();
        info5.setLength(10L);
        info5.setOffset(5L);
        info5.setEncodedMetadata("Encoded-Metadatas");
        info5.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        UploadInfo info6 = new UploadInfo();
        info6.setLength(10L);
        info6.setOffset(5L);
        info6.setEncodedMetadata("Encoded-Metadata");
        info6.setId(UUID.fromString("1911e8a4-6939-490c-c58b-a5d70f8d91fb"));

        assertTrue(info1.equals(info2));
        assertFalse(info1.equals(info3));
        assertFalse(info1.equals(info4));
        assertFalse(info1.equals(info5));
        assertFalse(info1.equals(info6));
    }

    @Test
    public void testHashCode() throws Exception {
        UploadInfo info1 = new UploadInfo();
        info1.setLength(10L);
        info1.setOffset(5L);
        info1.setEncodedMetadata("Encoded-Metadata");
        info1.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        UploadInfo info2 = new UploadInfo();
        info2.setLength(10L);
        info2.setOffset(5L);
        info2.setEncodedMetadata("Encoded-Metadata");
        info2.setId(UUID.fromString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));

        assertTrue(info1.hashCode() == info2.hashCode());
    }

}