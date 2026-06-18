## 2024-05-24 - [Insecure Deserialization Fix]
**Vulnerability:** The `Utils.readSerializable` function deserialized objects directly from `ObjectInputStream`, which can lead to insecure deserialization attacks if the stored data is tampered with.
**Learning:** `commons-io` provides `ValidatingObjectInputStream` to limit deserialization to specific allowed classes (`java.lang.*`, `java.util.*`, `me.desair.tus.server.upload.*`, `me.desair.tus.server.checksum.*`).
**Prevention:** Always use `ValidatingObjectInputStream` or equivalent mechanisms to restrict class loading when deserializing data, even if it comes from local storage, to prevent arbitrary code execution in case the storage layer is compromised.
