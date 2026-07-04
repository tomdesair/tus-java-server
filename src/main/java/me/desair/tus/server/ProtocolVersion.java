package me.desair.tus.server;

/** Enumeration of supported file upload protocol versions. */
public enum ProtocolVersion {
  /**
   * The Tus v1.0.0 upload protocol specification (https://tus.io/protocols/resumable-upload.html).
   */
  TUS_1_0_0("TUS-1.0.0"),

  /**
   * The official IETF Resumable Uploads for HTTP (RUFH) specification
   * (draft-ietf-httpbis-resumable-upload).
   */
  RUFH("RUFH"),

  /** Automatic protocol selection based on incoming request header indicators. */
  AUTO("AUTO");

  private final String name;

  ProtocolVersion(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
