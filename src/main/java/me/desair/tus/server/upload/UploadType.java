package me.desair.tus.server.upload;

/** Enum that lists all the possible upload types in the tus protocol */
public enum UploadType {
  /** REGULAR indicates a normal upload */
  REGULAR,

  /** PARTIAL indicates an upload that is part of a concatenated upload */
  PARTIAL,

  /** CONCATENATED is the upload that combines different partial uploads */
  CONCATENATED
}
