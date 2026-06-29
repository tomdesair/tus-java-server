
## 2026-06-29 - [Fix Insecure Deserialization in DiskStorageService]
**Vulnerability:** The application was using the standard `java.io.ObjectInputStream` to deserialize objects from disk (specifically in `Utils.readSerializable`), without restricting the classes that can be instantiated. This creates a critical insecure deserialization vulnerability that could lead to Remote Code Execution (RCE) if an attacker can tamper with the serialized files on disk.
**Learning:** The vulnerability existed because standard deserialization is dangerous by default and implicitly trusts the incoming data to dictate which classes are instantiated.
**Prevention:** Always use a validating deserialization mechanism, such as Apache Commons IO `ValidatingObjectInputStream`, to explicitly allowlist only the classes and packages that are safe and expected to be deserialized.
