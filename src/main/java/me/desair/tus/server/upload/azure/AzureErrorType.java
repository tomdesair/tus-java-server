package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.models.BlobStorageException;

/** Standardized Azure Blob Storage error types parsed from {@link BlobStorageException}. */
public enum AzureErrorType {
  /** Target blob or container does not exist (HTTP 404 / BlobNotFound / ContainerNotFound). */
  BLOB_NOT_FOUND,

  /**
   * Blob lease is already held by another client / lock contention (HTTP 409 / LeaseAlreadyPresent
   * / LeaseIdMismatchWithLeaseOperation).
   */
  LEASE_ALREADY_PRESENT,

  /** No active lease exists on the blob (HTTP 409 / LeaseNotPresentWithLeaseOperation). */
  LEASE_NOT_PRESENT,

  /** Conditional request precondition failed (HTTP 412 / ConditionNotMet). */
  PRECONDITION_FAILED,

  /** Operation or API method not implemented by server/emulator (HTTP 501 / APINotImplemented). */
  API_NOT_IMPLEMENTED,

  /** Permission denied or invalid SAS credentials (HTTP 403 / AuthorizationFailure). */
  ACCESS_DENIED,

  /** Lock or resource already exists / general conflict (HTTP 409 / BlobAlreadyExists). */
  CONFLICT,

  /** Unknown or unmapped Azure storage exception. */
  UNKNOWN
}
