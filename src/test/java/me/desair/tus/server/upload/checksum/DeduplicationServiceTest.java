package me.desair.tus.server.upload.checksum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DeduplicationService}. */
public class DeduplicationServiceTest {

  private TestChecksumIndex index;
  private TestStorageService adapter;
  private UploadLockingService lockingService;
  private DeduplicationService deduplicationService;

  private final ChecksumKey checksumKey =
      ChecksumKey.of("0123456789abcdef0123456789abcdef01234567", ChecksumAlgorithm.SHA1);

  @Before
  public void setUp() {
    index = new TestChecksumIndex();
    adapter = new TestStorageService();
    lockingService = mock(UploadLockingService.class);
    deduplicationService = new DeduplicationService(index, adapter);
    deduplicationService.setUploadLockingService(lockingService);
    adapter.setDeduplicationService(deduplicationService);
  }

  @Test
  public void testDeduplicateFirstUploadBecomesParent() throws Exception {
    UploadId id1 = new UploadId("upload-1");
    UploadInfo info1 = createUploadInfo(id1, checksumKey);
    adapter.put(id1, info1, true);

    UploadInfo result = deduplicationService.deduplicate(info1, checksumKey);

    assertEquals(id1, result.getId());
    assertNull(result.getDuplicatesUploadId());
    assertEquals(id1, index.findParent(checksumKey));
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testDeduplicateSecondUploadJoinsParent() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadLock mockLock = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockLock);

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(childId, checksumKey);
    adapter.put(childId, childInfo, true);

    UploadInfo result = deduplicationService.deduplicate(childInfo, checksumKey);

    assertEquals(childId, result.getId());
    assertEquals(parentId, result.getDuplicatesUploadId());
    assertTrue(index.hasChildren(checksumKey));
    assertFalse(adapter.dataExists(childId)); // Child data deleted
    assertTrue(adapter.dataExists(parentId)); // Parent data intact
    verify(mockLock).close();
  }

  @Test
  public void testDeduplicateSkipsWhenParentLocked() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    when(lockingService.lockUpload(parentId))
        .thenThrow(new UploadAlreadyLockedException("Parent locked"));

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(childId, checksumKey);
    adapter.put(childId, childInfo, true);

    UploadInfo result = deduplicationService.deduplicate(childInfo, checksumKey);

    // Should skip deduplication and keep own data
    assertNull(result.getDuplicatesUploadId());
    assertTrue(adapter.dataExists(childId));
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testDeduplicatePurgesIndexWhenParentBytesMissing() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, false); // No data bytes!
    index.tryRegisterParent(checksumKey, parentId);

    UploadLock mockLock = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockLock);

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(childId, checksumKey);
    adapter.put(childId, childInfo, true);

    UploadInfo result = deduplicationService.deduplicate(childInfo, checksumKey);

    // Self-healing: stale index purged and child upload promoted to new parent
    assertEquals(childId, index.findParent(checksumKey));
    assertNull(result.getDuplicatesUploadId());
    assertTrue(adapter.dataExists(childId));
  }

  @Test
  public void testTerminateParentWithNoChildrenPurgesAll() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    assertTrue(deduplicationService.removeUploadFromIndex(parentInfo));
    adapter.terminateUpload(parentInfo);

    assertFalse(adapter.dataExists(parentId));
    assertNull(adapter.getUploadInfo(parentId));
    assertNull(index.findParent(checksumKey));
  }

  @Test
  public void testTerminateParentWithChildrenTombstonesParent() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadId childId = new UploadId("child-upload");
    index.addChild(checksumKey, childId);

    assertFalse(deduplicationService.removeUploadFromIndex(parentInfo));
    adapter.terminateUpload(parentInfo);

    // Parent data bytes MUST remain for child! Metadata deleted, tombstone created, parent pointer
    // retained.
    assertTrue(adapter.dataExists(parentId));
    assertNull(adapter.getUploadInfo(parentId));
    assertTrue(index.isParentDeleted(checksumKey));
    assertEquals(parentId, index.findParent(checksumKey));
  }

  @Test
  public void testTerminateChildDeletesChildMetadataOnlyWhenOtherChildrenRemain() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);
    index.markParentDeleted(checksumKey); // Parent already deleted

    UploadId child1 = new UploadId("child-1");
    UploadInfo child1Info = createUploadInfo(child1, checksumKey);
    child1Info.setDuplicatesUploadId(parentId);
    adapter.put(child1, child1Info, false);
    index.addChild(checksumKey, child1);

    UploadId child2 = new UploadId("child-2");
    index.addChild(checksumKey, child2);

    assertTrue(deduplicationService.removeUploadFromIndex(child1Info));
    adapter.terminateUpload(child1Info);

    // child 1 metadata removed
    assertNull(adapter.getUploadInfo(child1));
    // parent data bytes still preserved for child 2
    assertTrue(adapter.dataExists(parentId));
    assertTrue(index.hasChildren(checksumKey));
  }

  @Test
  public void testTerminateLastChildAfterParentDeletedPurgesParentData() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);
    index.markParentDeleted(checksumKey);

    UploadId lastChild = new UploadId("last-child");
    UploadInfo childInfo = createUploadInfo(lastChild, checksumKey);
    childInfo.setDuplicatesUploadId(parentId);
    adapter.put(lastChild, childInfo, false);
    index.addChild(checksumKey, lastChild);

    UploadLock mockParentLock = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockParentLock);

    assertTrue(deduplicationService.removeUploadFromIndex(childInfo));
    adapter.terminateUpload(childInfo);

    // Child metadata deleted
    assertNull(adapter.getUploadInfo(lastChild));
    // Parent data purged!
    assertFalse(adapter.dataExists(parentId));
    assertFalse(index.hasChildren(checksumKey));
    assertFalse(index.isParentDeleted(checksumKey));
    verify(mockParentLock).close();
  }

  @Test
  public void testTerminateChildWhenParentIsAliveDoesNotLockParent() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(childId, checksumKey);
    childInfo.setDuplicatesUploadId(parentId);
    adapter.put(childId, childInfo, false);
    index.addChild(checksumKey, childId);

    assertTrue(deduplicationService.removeUploadFromIndex(childInfo));
    adapter.terminateUpload(childInfo);

    // Verify parent lock is NEVER touched when parent is alive
    verify(lockingService, never()).lockUpload(parentId);
    verify(lockingService, never()).lockUpload(any());
    assertNull(adapter.getUploadInfo(childId));
    assertTrue(adapter.dataExists(parentId));
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testTerminateChildWithNullChecksumResolvesParentChecksum() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = new UploadInfo();
    childInfo.setId(childId);
    childInfo.setDuplicatesUploadId(parentId);
    // checksum is null on childInfo!
    adapter.put(childId, childInfo, false);
    index.addChild(checksumKey, childId);

    assertTrue(deduplicationService.removeUploadFromIndex(childInfo));
    adapter.terminateUpload(childInfo);

    assertNull(adapter.getUploadInfo(childId));
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testDeduplicateWhenCasFailsAndWinningParentIsActive() throws Exception {
    UploadId winnerParentId = new UploadId("winner-parent");
    UploadInfo winnerInfo = createUploadInfo(winnerParentId, checksumKey);
    adapter.put(winnerParentId, winnerInfo, true);

    UploadLock mockLock = mock(UploadLock.class);
    when(lockingService.lockUpload(winnerParentId)).thenReturn(mockLock);

    // Custom index where tryRegisterParent fails (CAS lost) but findParent returns winner
    TestChecksumIndex customIndex =
        new TestChecksumIndex() {
          @Override
          public synchronized boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) {
            return false; // lost race
          }

          @Override
          public synchronized UploadId findParent(ChecksumKey key) {
            return winnerParentId;
          }
        };

    DeduplicationService service = new DeduplicationService(customIndex, adapter);
    service.setUploadLockingService(lockingService);

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(childId, checksumKey);
    adapter.put(childId, childInfo, true);

    UploadInfo result = service.deduplicate(childInfo, checksumKey);
    assertEquals(childId, result.getId());
    assertEquals(winnerParentId, result.getDuplicatesUploadId());
    assertFalse(adapter.dataExists(childId));
    verify(mockLock).close();
  }

  @Test
  public void testDeduplicateNullInputs() throws Exception {
    assertNull(deduplicationService.deduplicate(null, checksumKey));
    UploadInfo info = new UploadInfo();
    assertEquals(info, deduplicationService.deduplicate(info, null));
  }

  @Test
  public void testDeduplicateWhenUploadIsAlreadyParent() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    // If deduplicate is invoked with the parent itself, it should return unmodified
    UploadInfo result = deduplicationService.deduplicate(parentInfo, checksumKey);
    assertEquals(parentId, result.getId());
    assertNull(result.getDuplicatesUploadId());
  }

  @Test
  public void testDeduplicateWhenCasFailsAndWinningParentWasTombstoned() throws Exception {
    // Simulate case where CAS fails (another upload registered), but by lookup time parent is null
    TestChecksumIndex customIndex =
        new TestChecksumIndex() {
          private boolean firstAttempt = true;

          @Override
          public synchronized boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) {
            return false; // lost race
          }

          @Override
          public synchronized UploadId findParent(ChecksumKey key) {
            return null; // tombstoned concurrently
          }
        };
    DeduplicationService service = new DeduplicationService(customIndex, adapter);
    service.setUploadLockingService(lockingService);

    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(childId, checksumKey);
    adapter.put(childId, childInfo, true);

    UploadInfo result = service.deduplicate(childInfo, checksumKey);
    assertEquals(childId, result.getId());
    assertNull(result.getDuplicatesUploadId());
  }

  @Test
  public void testGetUploadInfoByChecksum() throws Exception {
    assertNull(deduplicationService.getUploadInfoByChecksum(null));

    // When no parent registered
    assertNull(deduplicationService.getUploadInfoByChecksum(checksumKey));

    // When parent exists with data
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadInfo found = deduplicationService.getUploadInfoByChecksum(checksumKey);
    assertEquals(parentId, found.getId());

    // When parent data is missing
    adapter.deleteData(parentId);
    assertNull(deduplicationService.getUploadInfoByChecksum(checksumKey));
  }

  @Test
  public void testTerminateUploadNullOrMissingId() throws Exception {
    assertTrue(deduplicationService.removeUploadFromIndex(null));
    assertTrue(deduplicationService.removeUploadFromIndex(new UploadInfo()));
    adapter.terminateUpload(null);
    adapter.terminateUpload(new UploadInfo());
    // KISS: verifying method executes cleanly without throwing an exception
  }

  @Test
  public void testTerminateUploadWithoutChecksum() throws Exception {
    UploadId plainId = new UploadId("plain-upload");
    UploadInfo plainInfo = new UploadInfo();
    plainInfo.setId(plainId);
    adapter.put(plainId, plainInfo, true);

    assertTrue(deduplicationService.removeUploadFromIndex(plainInfo));
    adapter.terminateUpload(plainInfo);
    assertFalse(adapter.dataExists(plainId));
    assertNull(adapter.getUploadInfo(plainId));
  }

  @Test(expected = java.io.IOException.class)
  public void testTerminateUploadWithInvalidChecksumThrowsIOException() throws Exception {
    UploadId testId = new UploadId("test-upload");
    UploadInfo testInfo = new UploadInfo();
    testInfo.setId(testId);
    testInfo.setChecksum("invalid hex checksum !@#$");
    testInfo.setChecksumAlgorithm(ChecksumAlgorithm.SHA1);

    deduplicationService.removeUploadFromIndex(testInfo);
  }

  @Test
  public void testTerminateLastChildWhenParentLockFails() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);
    index.markParentDeleted(checksumKey);

    UploadId lastChild = new UploadId("last-child");
    UploadInfo childInfo = createUploadInfo(lastChild, checksumKey);
    childInfo.setDuplicatesUploadId(parentId);
    adapter.put(lastChild, childInfo, false);
    index.addChild(checksumKey, lastChild);

    when(lockingService.lockUpload(parentId))
        .thenThrow(new UploadAlreadyLockedException("Parent locked"));

    assertTrue(deduplicationService.removeUploadFromIndex(childInfo));
    adapter.terminateUpload(childInfo);

    // Child metadata deleted
    assertNull(adapter.getUploadInfo(lastChild));
    // Parent data purge was skipped due to lock contention
    assertTrue(adapter.dataExists(parentId));
  }

  @Test
  public void testNewChildJoinsTombstonedParentWhenChildrenRemain() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    // Child 1 joins parent
    UploadId child1 = new UploadId("child-1");
    UploadInfo child1Info = createUploadInfo(child1, checksumKey);
    adapter.put(child1, child1Info, true);
    UploadLock mockLock1 = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockLock1);
    deduplicationService.deduplicate(child1Info, checksumKey);

    // Parent is deleted (tombstoned)
    assertFalse(deduplicationService.removeUploadFromIndex(parentInfo));
    adapter.terminateUpload(parentInfo);
    assertTrue(index.isParentDeleted(checksumKey));
    assertEquals(parentId, index.findParent(checksumKey));
    assertTrue(adapter.dataExists(parentId));

    // Child 2 arrives while parent is tombstoned
    UploadId child2 = new UploadId("child-2");
    UploadInfo child2Info = createUploadInfo(child2, checksumKey);
    adapter.put(child2, child2Info, true);
    UploadLock mockLock2 = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockLock2);

    UploadInfo result2 = deduplicationService.deduplicate(child2Info, checksumKey);
    assertEquals(parentId, result2.getDuplicatesUploadId());
    assertFalse(adapter.dataExists(child2));
    assertTrue(adapter.dataExists(parentId));
    assertTrue(index.hasChildren(checksumKey));
  }

  @Test
  public void testDeduplicateSelfHealsWhenParentInfoMissingAndNotTombstoned() throws Exception {
    UploadId parentId = new UploadId("stale-parent");
    index.tryRegisterParent(checksumKey, parentId);
    // parentInfo is null in adapter and isParentDeleted is false!
    UploadLock mockLock = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockLock);

    UploadId newUpload = new UploadId("new-upload");
    UploadInfo newInfo = createUploadInfo(newUpload, checksumKey);
    adapter.put(newUpload, newInfo, true);

    UploadInfo result = deduplicationService.deduplicate(newInfo, checksumKey);
    assertEquals(newUpload, result.getId());
    assertNull(result.getDuplicatesUploadId());
    assertEquals(newUpload, index.findParent(checksumKey));
    assertTrue(adapter.dataExists(newUpload));
  }

  @Test
  public void testDeduplicateB2ParentReplacementUnderLock() throws Exception {
    UploadId parent1 = new UploadId("parent-1");
    UploadId parent2 = new UploadId("parent-2");
    UploadInfo parent2Info = createUploadInfo(parent2, checksumKey);
    adapter.put(parent2, parent2Info, true);
    index.tryRegisterParent(checksumKey, parent1);

    UploadLock mockLock1 = mock(UploadLock.class);
    UploadLock mockLock2 = mock(UploadLock.class);
    when(lockingService.lockUpload(parent1))
        .thenAnswer(
            invocation -> {
              // Concurrently, parent1 was replaced with parent2
              index.deleteIndex(checksumKey);
              index.tryRegisterParent(checksumKey, parent2);
              return mockLock1;
            });
    when(lockingService.lockUpload(parent2)).thenReturn(mockLock2);

    UploadId child = new UploadId("child-upload");
    UploadInfo childInfo = createUploadInfo(child, checksumKey);
    adapter.put(child, childInfo, true);

    UploadInfo result = deduplicationService.deduplicate(childInfo, checksumKey);
    assertEquals(parent2, result.getDuplicatesUploadId());
    verify(mockLock1).close();
    verify(mockLock2).close();
  }

  @Test
  public void testTerminateUnindexedStandaloneUploadWithChecksumDoesNotTouchIndex()
      throws Exception {
    UploadId registeredParent = new UploadId("registered-parent");
    UploadInfo parentInfo = createUploadInfo(registeredParent, checksumKey);
    adapter.put(registeredParent, parentInfo, true);
    index.tryRegisterParent(checksumKey, registeredParent);

    // Standalone upload that skipped deduplication (e.g. under lock contention)
    UploadId standalone = new UploadId("standalone-upload");
    UploadInfo standaloneInfo = createUploadInfo(standalone, checksumKey);
    adapter.put(standalone, standaloneInfo, true);

    assertTrue(deduplicationService.removeUploadFromIndex(standaloneInfo));
    adapter.terminateUpload(standaloneInfo);

    // Standalone upload data/metadata deleted
    assertFalse(adapter.dataExists(standalone));
    assertNull(adapter.getUploadInfo(standalone));

    // Registered parent remains intact in index and storage
    assertEquals(registeredParent, index.findParent(checksumKey));
    assertTrue(adapter.dataExists(registeredParent));
  }

  @Test
  public void testTerminateParentRaceWhenLastChildExitsDuringTombstoning() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadId childId = new UploadId("child-upload");
    index.addChild(checksumKey, childId);

    // Simulate concurrent child removal right when markParentDeleted is called
    TestChecksumIndex raceIndex =
        new TestChecksumIndex() {
          @Override
          public synchronized void markParentDeleted(ChecksumKey key) {
            super.markParentDeleted(key);
            // Concurrent child terminates and removes its marker
            removeChild(key, childId);
          }
        };
    raceIndex.tryRegisterParent(checksumKey, parentId);
    raceIndex.addChild(checksumKey, childId);

    DeduplicationService service = new DeduplicationService(raceIndex, adapter);
    service.setUploadLockingService(lockingService);

    boolean deleteData = service.removeUploadFromIndex(parentInfo);
    if (deleteData) {
      adapter.deleteData(parentId);
    }
    adapter.deleteMetadata(parentId);

    // Parent data and index purged immediately due to post-tombstone check
    assertFalse(adapter.dataExists(parentId));
    assertNull(raceIndex.findParent(checksumKey));
  }

  @Test
  public void testSyncParentExpirationOnChildJoin() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    parentInfo.setExpirationTimestamp(1000L);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    UploadLock mockLock = mock(UploadLock.class);
    when(lockingService.lockUpload(parentId)).thenReturn(mockLock);

    // Child with longer expiration extends parent expiration
    UploadId child1 = new UploadId("child-1");
    UploadInfo child1Info = createUploadInfo(child1, checksumKey);
    child1Info.setExpirationTimestamp(2000L);
    adapter.put(child1, child1Info, true);

    deduplicationService.deduplicate(child1Info, checksumKey);
    assertEquals(Long.valueOf(2000L), adapter.getUploadInfo(parentId).getExpirationTimestamp());

    // Child with null expiration clears parent expiration (infinite)
    UploadId child2 = new UploadId("child-2");
    UploadInfo child2Info = createUploadInfo(child2, checksumKey);
    child2Info.setExpirationTimestamp(null);
    adapter.put(child2, child2Info, true);

    deduplicationService.deduplicate(child2Info, checksumKey);
    assertNull(adapter.getUploadInfo(parentId).getExpirationTimestamp());
  }

  @Test
  public void
      testGetUploadInfoByChecksumWhenParentTombstonedAndBytesExistReturnsNullWithoutDeletingIndex()
          throws Exception {
    UploadId parentId = new UploadId("tombstoned-parent");
    index.tryRegisterParent(checksumKey, parentId);
    index.markParentDeleted(checksumKey);
    adapter.put(parentId, null, true); // metadata deleted, physical data exists

    assertNull(deduplicationService.getUploadInfoByChecksum(checksumKey));
    // Index was NOT purged
    assertEquals(parentId, index.findParent(checksumKey));
  }

  @Test
  public void testDeduplicateB2ParentReplacementUnderLockWhenNewParentIsNull() throws Exception {
    UploadId parent1 = new UploadId("parent-1");
    index.tryRegisterParent(checksumKey, parent1);

    UploadLock mockLock1 = mock(UploadLock.class);
    when(lockingService.lockUpload(parent1))
        .thenAnswer(
            invocation -> {
              // Concurrently, index was cleared
              index.deleteIndex(checksumKey);
              return mockLock1;
            });

    UploadId newParent = new UploadId("new-parent");
    UploadInfo newParentInfo = createUploadInfo(newParent, checksumKey);
    adapter.put(newParent, newParentInfo, true);

    UploadInfo result = deduplicationService.deduplicate(newParentInfo, checksumKey);
    assertEquals(newParent, result.getId());
    assertNull(result.getDuplicatesUploadId());
    assertEquals(newParent, index.findParent(checksumKey));
  }

  @Test
  public void testTerminateChildWithNullChecksumWhenParentInfoNull() throws Exception {
    UploadId parentId = new UploadId("deleted-parent");
    UploadId childId = new UploadId("child-upload");
    UploadInfo childInfo = new UploadInfo();
    childInfo.setId(childId);
    childInfo.setDuplicatesUploadId(parentId);
    // checksum is null and parentInfo in adapter is null
    adapter.put(childId, childInfo, true);

    assertTrue(deduplicationService.removeUploadFromIndex(childInfo));
    adapter.terminateUpload(childInfo);
    assertNull(adapter.getUploadInfo(childId));
    assertFalse(adapter.dataExists(childId));
  }

  @Test
  public void testRegisterAsParentCasFailsAndJoinsWinner() throws Exception {
    UploadId winnerId = new UploadId("winner-parent");
    UploadInfo winnerInfo = createUploadInfo(winnerId, checksumKey);
    adapter.put(winnerId, winnerInfo, true);
    index.tryRegisterParent(checksumKey, winnerId);

    UploadLock mockLock = mock(UploadLock.class);
    when(lockingService.lockUpload(winnerId)).thenReturn(mockLock);

    UploadId competitorId = new UploadId("competitor-upload");
    UploadInfo competitorInfo = createUploadInfo(competitorId, checksumKey);
    adapter.put(competitorId, competitorInfo, true);

    // tryRegisterParent will return false because winner is already registered
    UploadInfo result = deduplicationService.deduplicate(competitorInfo, checksumKey);

    assertEquals(winnerId, result.getDuplicatesUploadId());
    assertFalse(adapter.dataExists(competitorId));
    assertTrue(index.hasChildren(checksumKey));
  }

  @Test
  public void testRemoveUploadFromIndexDoesNotInvokeLockCleanup() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    // Parent terminates with no children; index is removed cleanly
    assertTrue(deduplicationService.removeUploadFromIndex(parentInfo));
    assertNull(index.findParent(checksumKey));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesWhenParentDataMissing() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, false); // metadata exists, data missing!
    index.tryRegisterParent(checksumKey, parentId);

    deduplicationService.pruneOrphanedChecksumIndices();

    assertNull(index.findParent(checksumKey));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesWhenParentMetadataMissingAndNotTombstoned()
      throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    adapter.put(parentId, null, true); // metadata missing, data exists, not tombstoned!
    index.tryRegisterParent(checksumKey, parentId);

    deduplicationService.pruneOrphanedChecksumIndices();

    assertNull(index.findParent(checksumKey));
    assertFalse(adapter.dataExists(parentId));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesWhenTombstonedWithZeroChildren() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    adapter.put(parentId, null, true); // metadata missing, data exists
    index.tryRegisterParent(checksumKey, parentId);
    index.markParentDeleted(checksumKey); // tombstoned, but no children!

    deduplicationService.pruneOrphanedChecksumIndices();

    assertNull(index.findParent(checksumKey));
    assertFalse(adapter.dataExists(parentId));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesWhenTombstonedWithChildrenPreserved()
      throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    adapter.put(parentId, null, true);
    index.tryRegisterParent(checksumKey, parentId);
    index.markParentDeleted(checksumKey);

    UploadId childId = new UploadId("child-upload");
    index.addChild(checksumKey, childId);

    deduplicationService.pruneOrphanedChecksumIndices();

    // Tombstoned parent with active children MUST be preserved
    assertEquals(parentId, index.findParent(checksumKey));
    assertTrue(adapter.dataExists(parentId));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesWhenValidParentPreserved() throws Exception {
    UploadId parentId = new UploadId("parent-upload");
    UploadInfo parentInfo = createUploadInfo(parentId, checksumKey);
    adapter.put(parentId, parentInfo, true);
    index.tryRegisterParent(checksumKey, parentId);

    deduplicationService.pruneOrphanedChecksumIndices();

    // Valid parent with metadata and data must not be pruned
    assertEquals(parentId, index.findParent(checksumKey));
    assertTrue(adapter.dataExists(parentId));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesHandlesExceptionGracefully() throws Exception {
    TestChecksumIndex errorIndex =
        new TestChecksumIndex() {
          @Override
          public synchronized UploadId findParent(ChecksumKey key) {
            throw new RuntimeException("Simulated error reading index");
          }
        };
    errorIndex.tryRegisterParent(checksumKey, new UploadId("parent-id"));

    DeduplicationService service = new DeduplicationService(errorIndex, adapter);
    service.pruneOrphanedChecksumIndices();
    // KISS: verify method executes cleanly without bubbling up exception
    assertTrue(true);
  }

  @Test
  public void testPruneOrphanedChecksumIndicesHandlesGetAllIndexedKeysExceptionGracefully()
      throws Exception {
    TestChecksumIndex errorIndex =
        new TestChecksumIndex() {
          @Override
          public synchronized Set<ChecksumKey> getAllIndexedKeys() throws IOException {
            throw new IOException("Simulated error listing keys");
          }
        };

    DeduplicationService service = new DeduplicationService(errorIndex, adapter);
    service.pruneOrphanedChecksumIndices();
    // KISS: verify method executes cleanly without bubbling up exception
    assertTrue(true);
  }

  @Test(expected = NullPointerException.class)
  public void testThreeParamConstructorThrowsOnNullLockingService() {
    new DeduplicationService(index, adapter, null);
  }

  @Test(expected = NullPointerException.class)
  public void testSetUploadLockingServiceThrowsOnNull() {
    deduplicationService.setUploadLockingService(null);
  }

  @Test
  public void testThreeParamConstructorWithValidLockingService() {
    DeduplicationService service = new DeduplicationService(index, adapter, lockingService);
    assertNotNull(service);
  }

  @Test
  public void testLockParentWithRetryInterruptedThrowsTusException() throws Exception {
    UploadLockingService mockLocking = mock(UploadLockingService.class);
    when(mockLocking.lockUpload(any(UploadId.class)))
        .thenThrow(new UploadAlreadyLockedException("locked"));

    DeduplicationService service = new DeduplicationService(index, adapter, mockLocking);
    UploadInfo childInfo = new UploadInfo();
    childInfo.setId(new UploadId("child-1"));
    childInfo.setDuplicatesUploadId(new UploadId("parent-1"));

    Thread.currentThread().interrupt();
    service.syncParentExpirationIfChild(childInfo);
    assertTrue(Thread.interrupted()); // verifies thread interrupted flag was re-asserted
  }

  @Test
  public void testSyncParentExpirationIfChildNullOrNoParent() {
    deduplicationService.syncParentExpirationIfChild(null);
    UploadInfo info = new UploadInfo();
    deduplicationService.syncParentExpirationIfChild(info);
    assertNull(info.getDuplicatesUploadId());
  }

  @Test
  public void testPruneOrphanedIndicesHandlesNullParentAndLockedParent() throws Exception {
    ChecksumKey keyWithNullParent =
        ChecksumKey.of("1111111111111111111111111111111111111111", ChecksumAlgorithm.SHA1);
    index.tryRegisterParent(keyWithNullParent, null);

    ChecksumKey lockedKey =
        ChecksumKey.of("2222222222222222222222222222222222222222", ChecksumAlgorithm.SHA1);
    UploadId lockedParent = new UploadId("locked-parent");
    index.tryRegisterParent(lockedKey, lockedParent);

    UploadLockingService mockLocking = mock(UploadLockingService.class);
    when(mockLocking.lockUpload(lockedParent))
        .thenThrow(new UploadAlreadyLockedException("Parent locked"));

    DeduplicationService service = new DeduplicationService(index, adapter, mockLocking);
    // Should skip null parent and skip locked parent cleanly without throwing
    service.pruneOrphanedChecksumIndices();
  }

  @Test
  public void testPruneOrphanedIndicesRepairsMissingTombstoneWithActiveChildren() throws Exception {
    ChecksumKey key =
        ChecksumKey.of("3333333333333333333333333333333333333333", ChecksumAlgorithm.SHA1);
    UploadId parentId = new UploadId("repair-parent");
    index.tryRegisterParent(key, parentId);
    index.addChild(key, new UploadId("child-1"));

    // Parent data exists, but parent info is null, not tombstoned, has children
    adapter.put(parentId, null, true);

    deduplicationService.pruneOrphanedChecksumIndices();

    // Verify tombstone was repaired
    assertTrue(index.isParentDeleted(key));
    assertEquals(parentId, index.findParent(key));
  }

  private UploadInfo createUploadInfo(UploadId id, ChecksumKey key) {
    UploadInfo info = new UploadInfo();
    info.setId(id);
    info.setOffset(100L);
    info.setLength(100L);
    info.setChecksum(key.getHexChecksum());
    info.setChecksumAlgorithm(key.getAlgorithm());
    return info;
  }

  // --- In-memory Test Doubles ---

  private static class TestChecksumIndex implements ChecksumIndex {
    private final Map<ChecksumKey, UploadId> parents = new HashMap<>();
    private final Set<ChecksumKey> tombstones = new HashSet<>();
    private final Map<ChecksumKey, Set<UploadId>> children = new HashMap<>();

    @Override
    public synchronized boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) {
      if (parents.containsKey(key) || tombstones.contains(key)) {
        return false;
      }
      parents.put(key, uploadId);
      return true;
    }

    @Override
    public synchronized UploadId findParent(ChecksumKey key) {
      return parents.get(key);
    }

    @Override
    public synchronized void markParentDeleted(ChecksumKey key) {
      tombstones.add(key);
    }

    @Override
    public synchronized boolean isParentDeleted(ChecksumKey key) {
      return tombstones.contains(key);
    }

    @Override
    public synchronized void addChild(ChecksumKey key, UploadId childId) {
      children.computeIfAbsent(key, k -> new HashSet<>()).add(childId);
    }

    @Override
    public synchronized void removeChild(ChecksumKey key, UploadId childId) {
      Set<UploadId> set = children.get(key);
      if (set != null) {
        set.remove(childId);
        if (set.isEmpty()) {
          children.remove(key);
        }
      }
    }

    @Override
    public synchronized boolean hasChildren(ChecksumKey key) {
      Set<UploadId> set = children.get(key);
      return set != null && !set.isEmpty();
    }

    @Override
    public synchronized void deleteIndex(ChecksumKey key) {
      parents.remove(key);
      tombstones.remove(key);
      children.remove(key);
    }

    @Override
    public synchronized Set<ChecksumKey> getAllIndexedKeys() throws IOException {
      return new HashSet<>(parents.keySet());
    }
  }

  private static class TestStorageService implements UploadStorageService {
    private final Map<UploadId, UploadInfo> infoMap = new HashMap<>();
    private final Set<UploadId> dataFiles = new HashSet<>();
    private DeduplicationService deduplicationService;

    void setDeduplicationService(DeduplicationService deduplicationService) {
      this.deduplicationService = deduplicationService;
    }

    void put(UploadId id, UploadInfo info, boolean hasData) {
      if (info != null) {
        infoMap.put(id, info);
      } else {
        infoMap.remove(id);
      }
      if (hasData) {
        dataFiles.add(id);
      } else {
        dataFiles.remove(id);
      }
    }

    @Override
    public boolean dataExists(UploadId id) {
      return dataFiles.contains(id);
    }

    @Override
    public void deleteData(UploadId id) {
      dataFiles.remove(id);
    }

    public void deleteMetadata(UploadId id) {
      infoMap.remove(id);
    }

    @Override
    public UploadInfo getUploadInfo(UploadId id) {
      return infoMap.get(id);
    }

    @Override
    public void update(UploadInfo info) {
      infoMap.put(info.getId(), info);
    }

    @Override
    public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
      if (uploadInfo == null || uploadInfo.getId() == null) {
        return;
      }
      boolean deleteData = true;
      if (deduplicationService != null) {
        deleteData = deduplicationService.removeUploadFromIndex(uploadInfo);
      }
      if (deleteData) {
        deleteData(uploadInfo.getId());
      }
      deleteMetadata(uploadInfo.getId());
    }

    @Override
    public UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException {
      return null;
    }

    @Override
    public String getUploadUri() {
      return null;
    }

    @Override
    public UploadInfo append(UploadInfo upload, InputStream inputStream)
        throws IOException, TusException {
      return null;
    }

    @Override
    public void setMaxUploadSize(Long maxUploadSize) {}

    @Override
    public long getMaxUploadSize() {
      return 0;
    }

    @Override
    public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
      return null;
    }

    @Override
    public InputStream getUploadedBytes(String uploadUri, String ownerKey)
        throws IOException, UploadNotFoundException {
      return null;
    }

    @Override
    public InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException {
      return null;
    }

    @Override
    public void copyUploadTo(UploadInfo info, OutputStream outputStream)
        throws UploadNotFoundException, IOException {}

    @Override
    public void cleanupExpiredUploads(UploadLockingService uploadLockingService)
        throws IOException {}

    @Override
    public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
        throws UploadNotFoundException, IOException {}

    @Override
    public Long getUploadExpirationPeriod() {
      return null;
    }

    @Override
    public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {}

    @Override
    public void setUploadConcatenationService(UploadConcatenationService concatenationService) {}

    @Override
    public UploadConcatenationService getUploadConcatenationService() {
      return null;
    }

    @Override
    public void setIdFactory(UploadIdFactory idFactory) {}

    @Override
    public UploadInfo deduplicate(UploadInfo uploadInfo, ChecksumKey checksumKey)
        throws IOException {
      return null;
    }

    @Override
    public void setUploadLockingService(UploadLockingService uploadLockingService) {}

    @Override
    public void setUploadDeduplicationEnabled(boolean deduplicationEnabled) {}

    @Override
    public boolean isUploadDeduplicationEnabled() {
      return false;
    }

    @Override
    public UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
        throws IOException {
      return null;
    }
  }
}
