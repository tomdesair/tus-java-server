# Documentation Index

## I want to use the library
1. [README](../README.md): what it is, quick start (Spring Boot, plain Servlet, curl)
2. [Integration Guide](INTEGRATION_GUIDE.md): `process()`, completion handling, cleanup, security, frontend clients
3. [Configuration Reference](CONFIGURATION.md): all options and storage backend choice
4. [Deployment Guide](DEPLOYMENT.md): multi-replica, reverse proxies, containers
5. [Troubleshooting](TROUBLESHOOTING.md)
6. [Architecture Guide](ARCHITECTURE.md): system structure, protocol extensions, storage SPI, and distributed locking

## Protocols
- [Protocols](PROTOCOLS.md): Tus 1.0.0 & IETF RUFH flows, comparison, extensions, digests
- [Migrating clients to RUFH](MIGRATION.md): header mapping and request examples
- [HTTP 104 Interim Responses](INTERIM_RESPONSES.md)

## Upgrading
- [Upgrade Guide 1.x → 2.0](UPGRADING.md) and the [CHANGELOG](../CHANGELOG.md)

## Storage & locking
- [Locking concepts & custom lock services](LOCKING.md)
- [Disk & network storage (NFS, EFS, SMB)](DISK_BASED_LOCKING.md)
- [S3-compatible storage](S3_STORAGE.md)
- [Azure Blob Storage](AZURE_BLOB_STORAGE.md)
- [Upload Deduplication & Migration](DEDUPLICATION.md)

## Contributors & maintainers
- [Architecture Guide](ARCHITECTURE.md), [CONTRIBUTING](../CONTRIBUTING.md), [Testing guide](TESTING.md), [Conformity testing](CONFORMITY_TESTING.md), [Release process](RELEASE.md)
