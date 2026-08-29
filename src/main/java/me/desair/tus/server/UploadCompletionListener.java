package me.desair.tus.server;

import me.desair.tus.server.upload.UploadInfo;

/**
 * Functional interface for listening to upload completion events across both Tus 1.0.0 and IETF
 * Resumable Uploads for HTTP (RUFH) protocols.
 */
@FunctionalInterface
public interface UploadCompletionListener {

  /**
   * Invoked when an upload has successfully completed.
   *
   * @param uploadInfo the metadata and identifiers of the completed upload
   * @param tusFileUploadService the service instance that processed the upload
   */
  void onUploadComplete(UploadInfo uploadInfo, TusFileUploadService tusFileUploadService);
}
