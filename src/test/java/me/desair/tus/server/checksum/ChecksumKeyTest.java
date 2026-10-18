package me.desair.tus.server.checksum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** Unit tests for {@link ChecksumKey}. */
public class ChecksumKeyTest {

  @Test
  public void testHexChecksumNormalization() {
    ChecksumKey key = ChecksumKey.of("ABCDEF0123456789", ChecksumAlgorithm.SHA1);
    assertEquals("abcdef0123456789", key.getHexChecksum());
    assertEquals(ChecksumAlgorithm.SHA1, key.getAlgorithm());
    assertEquals("sha1/abcdef0123456789", key.toString());
  }

  @Test
  public void testHexChecksumWithWhitespace() {
    ChecksumKey key = ChecksumKey.of("  abcdef0123456789  ", ChecksumAlgorithm.SHA256);
    assertEquals("abcdef0123456789", key.getHexChecksum());
    assertEquals(ChecksumAlgorithm.SHA256, key.getAlgorithm());
  }

  @Test
  public void testBase64ChecksumDecoding() {
    // "hello" in ASCII bytes is 68 65 6c 6c 6f. Base64 is "aGVsbG8="
    ChecksumKey key = ChecksumKey.of("aGVsbG8=", ChecksumAlgorithm.SHA1);
    assertEquals("68656c6c6f", key.getHexChecksum());
  }

  @Test
  public void testEqualsAndHashCode() {
    ChecksumKey key1 = ChecksumKey.of("abcdef", ChecksumAlgorithm.SHA1);
    ChecksumKey key2 = ChecksumKey.of("ABCDEF", ChecksumAlgorithm.SHA1);
    ChecksumKey key3 = ChecksumKey.of("abcdef", ChecksumAlgorithm.SHA256);

    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());
    assertNotEquals(key1, key3);
    assertNotEquals(key1, null);
    assertNotEquals(key1, "not-a-checksum-key");
  }

  @Test(expected = NullPointerException.class)
  public void testNullAlgorithm() {
    ChecksumKey.of("abcdef", null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNullChecksum() {
    ChecksumKey.of(null, ChecksumAlgorithm.SHA1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyChecksum() {
    ChecksumKey.of("   ", ChecksumAlgorithm.SHA1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidCharacters() {
    ChecksumKey.of("invalid!@#$", ChecksumAlgorithm.SHA1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testPathTraversalAttempt() {
    ChecksumKey.of("../../../etc/passwd", ChecksumAlgorithm.SHA1);
  }
}
