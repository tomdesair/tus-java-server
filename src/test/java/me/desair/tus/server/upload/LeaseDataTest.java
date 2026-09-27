package me.desair.tus.server.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link LeaseData} verifying all accessors, expiration logic, and equality
 * contracts.
 */
public class LeaseDataTest {

  @Test
  public void testConstructorsAndAccessors() {
    LeaseData empty = new LeaseData();
    empty.setHolderId("h1");
    empty.setRequestUri("/uri/1");
    empty.setLockPath("/path/1");
    empty.setStopPath("/stop/1");
    empty.setLeaseDurationMs(30000L);
    empty.setExpiresAt(60000L);
    empty.setAcquiredAt(30000L);

    assertEquals("h1", empty.getHolderId());
    assertEquals("/uri/1", empty.getRequestUri());
    assertEquals("/path/1", empty.getLockPath());
    assertEquals("/stop/1", empty.getStopPath());
    assertEquals(30000L, empty.getLeaseDurationMs());
    assertEquals(60000L, empty.getExpiresAt());
    assertEquals(30000L, empty.getAcquiredAt());

    LeaseData full = new LeaseData("h2", "/uri/2", 15000L, 50000L, 35000L, "/path/2", "/stop/2");
    assertEquals("h2", full.getHolderId());
    assertEquals("/uri/2", full.getRequestUri());
    assertEquals(15000L, full.getLeaseDurationMs());
    assertEquals(50000L, full.getExpiresAt());
    assertEquals(35000L, full.getAcquiredAt());
    assertEquals("/path/2", full.getLockPath());
    assertEquals("/stop/2", full.getStopPath());

    LeaseData shortConst = new LeaseData("h3", "/uri/3", 20000L, 40000L);
    assertEquals("h3", shortConst.getHolderId());
    assertEquals("/uri/3", shortConst.getRequestUri());
    assertEquals(20000L, shortConst.getLeaseDurationMs());
    assertEquals(40000L, shortConst.getExpiresAt());
    assertTrue(shortConst.getAcquiredAt() > 0L);
  }

  @Test
  public void testIsExpired() {
    LeaseData lease = new LeaseData("h", "/uri", 10000L, 50000L);

    // Baseline expiration without safety margin
    assertFalse(lease.isExpired(49999L));
    assertFalse(lease.isExpired(50000L));
    assertTrue(lease.isExpired(50001L));

    // Expiration with 2000ms safety margin
    assertFalse(lease.isExpired(50000L, 2000L));
    assertFalse(lease.isExpired(51999L, 2000L));
    assertFalse(lease.isExpired(52000L, 2000L));
    assertTrue(lease.isExpired(52001L, 2000L));
  }

  @Test
  public void testEqualsAndHashCode() {
    LeaseData l1 = new LeaseData("h1", "/uri", 10000L, 50000L, 40000L, "/p", "/s");
    LeaseData l2 = new LeaseData("h1", "/uri", 10000L, 50000L, 40000L, "/p", "/s");

    // Reflexive
    assertEquals(l1, l1);
    // Symmetric
    assertEquals(l1, l2);
    assertEquals(l2, l1);
    assertEquals(l1.hashCode(), l2.hashCode());

    // Null and different class
    assertNotEquals(null, l1);
    assertNotEquals("not-a-lease", l1);

    // Differences
    assertNotEquals(l1, new LeaseData("other", "/uri", 10000L, 50000L, 40000L, "/p", "/s"));
    assertNotEquals(l1, new LeaseData("h1", "/other", 10000L, 50000L, 40000L, "/p", "/s"));
    assertNotEquals(l1, new LeaseData("h1", "/uri", 20000L, 50000L, 40000L, "/p", "/s"));
    assertNotEquals(l1, new LeaseData("h1", "/uri", 10000L, 60000L, 40000L, "/p", "/s"));
    assertNotEquals(l1, new LeaseData("h1", "/uri", 10000L, 50000L, 30000L, "/p", "/s"));
    assertNotEquals(l1, new LeaseData("h1", "/uri", 10000L, 50000L, 40000L, "/other", "/s"));
    assertNotEquals(l1, new LeaseData("h1", "/uri", 10000L, 50000L, 40000L, "/p", "/other"));
  }
}
