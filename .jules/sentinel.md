## 2026-06-26 - [Insecure Deserialization in Utils]
**Vulnerability:** Insecure Java deserialization in `Utils.readSerializable` using `ObjectInputStream`.
**Learning:** The application serializes and deserializes internal state objects (like `UploadInfo`) to disk. When the file is read, a standard `ObjectInputStream` was used, which could lead to Remote Code Execution (RCE) if an attacker can write malicious serialized data to the disk.
**Prevention:** Always use `ValidatingObjectInputStream` from `commons-io` to enforce a strict whitelist of accepted classes during deserialization.
