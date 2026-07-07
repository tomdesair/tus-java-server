package me.desair.tus.server.checksum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class ChecksumAlgorithmTest {

  @Test
  public void getMessageDigest() throws Exception {
    assertNotNull(ChecksumAlgorithm.MD5.getMessageDigest());
    assertNotNull(ChecksumAlgorithm.SHA1.getMessageDigest());
    assertNotNull(ChecksumAlgorithm.SHA256.getMessageDigest());
    assertNotNull(ChecksumAlgorithm.SHA384.getMessageDigest());
    assertNotNull(ChecksumAlgorithm.SHA512.getMessageDigest());
  }

  @Test
  public void forTusName() throws Exception {
    assertEquals(ChecksumAlgorithm.MD5, ChecksumAlgorithm.forTusName("md5"));
    assertEquals(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.forTusName("sha1"));
    assertEquals(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.forTusName("sha256"));
    assertEquals(ChecksumAlgorithm.SHA384, ChecksumAlgorithm.forTusName("sha384"));
    assertEquals(ChecksumAlgorithm.SHA512, ChecksumAlgorithm.forTusName("sha512"));
    assertEquals(null, ChecksumAlgorithm.forTusName("test"));
  }

  @Test
  public void forUploadChecksumHeader() throws Exception {
    assertEquals(
        ChecksumAlgorithm.MD5, ChecksumAlgorithm.forUploadChecksumHeader("md5 1234567890"));
    assertEquals(
        ChecksumAlgorithm.SHA1, ChecksumAlgorithm.forUploadChecksumHeader("sha1 1234567890"));
    assertEquals(
        ChecksumAlgorithm.SHA256, ChecksumAlgorithm.forUploadChecksumHeader("sha256 1234567890"));
    assertEquals(
        ChecksumAlgorithm.SHA384, ChecksumAlgorithm.forUploadChecksumHeader("sha384 1234567890"));
    assertEquals(
        ChecksumAlgorithm.SHA512, ChecksumAlgorithm.forUploadChecksumHeader("sha512 1234567890"));
    assertEquals(null, ChecksumAlgorithm.forUploadChecksumHeader("test 1234567890"));
  }

  @Test
  public void testToString() throws Exception {
    assertEquals("md5", ChecksumAlgorithm.MD5.toString());
    assertEquals("sha1", ChecksumAlgorithm.SHA1.toString());
    assertEquals("sha256", ChecksumAlgorithm.SHA256.toString());
    assertEquals("sha384", ChecksumAlgorithm.SHA384.toString());
    assertEquals("sha512", ChecksumAlgorithm.SHA512.toString());
  }

  @Test
  public void testForHttpDigestName() {
    assertEquals(ChecksumAlgorithm.MD5, ChecksumAlgorithm.forHttpDigestName("md5"));
    assertEquals(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.forHttpDigestName("sha"));
    assertEquals(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.forHttpDigestName("sha1"));
    assertEquals(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.forHttpDigestName("sha-1"));
    assertEquals(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.forHttpDigestName("sha-256"));
    assertEquals(ChecksumAlgorithm.SHA384, ChecksumAlgorithm.forHttpDigestName("sha-384"));
    assertEquals(ChecksumAlgorithm.SHA512, ChecksumAlgorithm.forHttpDigestName("sha-512"));
    assertEquals(null, ChecksumAlgorithm.forHttpDigestName("unknown"));
    assertEquals(null, ChecksumAlgorithm.forHttpDigestName(null));
  }

  @Test
  public void testGetSupportedHttpDigestAlgorithmsHeaderValue() {
    assertEquals(
        "sha-256, sha-512, sha-384, sha, md5",
        ChecksumAlgorithm.getSupportedHttpDigestAlgorithmsHeaderValue());
  }

  @Test
  public void testParseDigestHeader() {
    java.util.Map<ChecksumAlgorithm, String> map =
        ChecksumAlgorithm.parseDigestHeader("sha-256=:foo=:, sha-512=:bar=:");
    assertEquals(2, map.size());
    assertEquals("foo=", map.get(ChecksumAlgorithm.SHA256));
    assertEquals("bar=", map.get(ChecksumAlgorithm.SHA512));

    java.util.Map<ChecksumAlgorithm, String> map2 =
        ChecksumAlgorithm.parseDigestHeader("unknown=:abc=:, sha-256=:foo=:");
    assertEquals(1, map2.size());
    assertEquals("foo=", map2.get(ChecksumAlgorithm.SHA256));

    assertEquals(0, ChecksumAlgorithm.parseDigestHeader("   ").size());
    assertEquals(0, ChecksumAlgorithm.parseDigestHeader(null).size());
  }

  @Test
  public void testCleanDigestValue() {
    assertEquals(null, ChecksumAlgorithm.cleanDigestValue(null));
    assertEquals("sha256=", ChecksumAlgorithm.cleanDigestValue(":sha256=:"));
    assertEquals("abc", ChecksumAlgorithm.cleanDigestValue("   :abc:   "));
    assertEquals("sha256=", ChecksumAlgorithm.cleanDigestValue(":sha256="));
    assertEquals("sha256=", ChecksumAlgorithm.cleanDigestValue("sha256=:"));
    assertEquals("plain", ChecksumAlgorithm.cleanDigestValue("plain"));
  }

  @Test
  public void testSelectBestAlgorithmDefaultWeight() {
    // No weight specified → defaults to q=1.0
    assertEquals(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.selectBestAlgorithm("sha-256"));
  }

  @Test
  public void testSelectBestAlgorithmExplicitWeight() {
    // Explicit weight: sha-512 has higher q
    assertEquals(
        ChecksumAlgorithm.SHA512,
        ChecksumAlgorithm.selectBestAlgorithm("sha-256;q=0.5, sha-512;q=0.8"));
  }

  @Test
  public void testSelectBestAlgorithmZeroWeightExcluded() {
    // q=0 means "not acceptable" per RFC 9530
    assertEquals(null, ChecksumAlgorithm.selectBestAlgorithm("sha-256;q=0"));
  }

  @Test
  public void testSelectBestAlgorithmMixedWeights() {
    // Mixed weights, sha-384 wins at q=0.9
    assertEquals(
        ChecksumAlgorithm.SHA384,
        ChecksumAlgorithm.selectBestAlgorithm("sha-256;q=0.3, sha-384;q=0.9, md5;q=0.1"));
  }

  @Test
  public void testSelectBestAlgorithmDefaultWinsOverExplicit() {
    // sha-256 has no q → defaults to 1.0, which beats sha-512 at q=0.5
    assertEquals(
        ChecksumAlgorithm.SHA256, ChecksumAlgorithm.selectBestAlgorithm("sha-256, sha-512;q=0.5"));
  }

  @Test
  public void testSelectBestAlgorithmUnsupportedOnly() {
    assertEquals(null, ChecksumAlgorithm.selectBestAlgorithm("unknown-alg"));
  }

  @Test
  public void testSelectBestAlgorithmNullAndBlank() {
    assertEquals(null, ChecksumAlgorithm.selectBestAlgorithm(null));
    assertEquals(null, ChecksumAlgorithm.selectBestAlgorithm("   "));
  }

  @Test
  public void testSelectBestAlgorithmInvalidQValue() {
    // Invalid q value falls back to q=1.0
    assertEquals(
        ChecksumAlgorithm.SHA256, ChecksumAlgorithm.selectBestAlgorithm("sha-256;q=notanumber"));
  }

  @Test
  public void testSelectBestAlgorithmNonQParameter() {
    // Non-q parameter after semicolon → defaults to q=1.0
    assertEquals(
        ChecksumAlgorithm.SHA256, ChecksumAlgorithm.selectBestAlgorithm("sha-256;level=1"));
  }

  @Test
  public void testSelectBestAlgorithmTieBreaking() {
    // When weights are equal (or default to 1.0), SHA-256 (priority 5) wins over SHA-512 (priority
    // 4)
    assertEquals(
        ChecksumAlgorithm.SHA256, ChecksumAlgorithm.selectBestAlgorithm("sha-512, sha-256"));
    assertEquals(
        ChecksumAlgorithm.SHA256, ChecksumAlgorithm.selectBestAlgorithm("sha-256, sha-512"));

    // Explicit equal weights: SHA-256 wins over SHA-512
    assertEquals(
        ChecksumAlgorithm.SHA256,
        ChecksumAlgorithm.selectBestAlgorithm("sha-512;q=0.8, sha-256;q=0.8"));
  }
}
