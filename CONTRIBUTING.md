# Contributing

This library comes without any warranty and is released under a [MIT license](https://github.com/tomdesair/tus-java-server/blob/master/LICENSE). If you encounter any bugs or if you have an idea for a useful improvement you are welcome to [open a new issue](https://github.com/tomdesair/tus-java-server/issues) or to [create a pull request](https://github.com/tomdesair/tus-java-server/pulls) with the proposed implementation. Please note that any contributed code needs to be accompanied by automated unit and/or integration tests and comply with the [defined code-style](#code-style).

Useful guides:
- [Architecture guide](docs/ARCHITECTURE.md)
- [Testing guide](docs/TESTING.md) and coverage checks
- [Protocol conformity testing](docs/CONFORMITY_TESTING.md)
- [Release process](docs/RELEASE.md)

## System Architecture & Where to Contribute

Before proposing design changes or implementing new features, please read the **[Architecture Guide](docs/ARCHITECTURE.md)**. It provides a complete overview of:
- The single facade service class (`TusFileUploadService`) and the dual-protocol extension pipeline.
- How core business logic is encapsulated in `RequestValidator` and `RequestHandler` implementations.
- The pluggable storage architecture (including both lease-based and legacy file-lock-based disk implementations, S3, and Azure Blob).
- The `AbstractLeaseLockingService` framework for distributed lease-based locking and lock contention handling.
- The contributor blueprint mapping common extension scenarios to the right packages and classes.

## Branching Strategy

Any new feature, bugfix, or improvement should be developed in a separate branch that starts with either `feature/` or `bugfix/` and has a meaningful, concise name (e.g., `feature/lock-contention-resolution` or `bugfix/fix-upload-timeout`).

## Code Style

All pull requests should have the correct formatting according to [Google Java Style](https://github.com/google/google-java-format) code formatting. To verify if the code style is correct run:

```bash
mvn -P codestyle com.spotify.fmt:fmt-maven-plugin:check
```

To reformat your code run:

```bash
mvn -P codestyle com.spotify.fmt:fmt-maven-plugin:format
```

See the [Google Java Style Github page](https://github.com/google/google-java-format) on recommendations on how to configure this in your IDE. Or if you have Python 3, you can also use [pre-commit](https://pre-commit.com) to make your life easier:

```bash
pip install pre-commit
pre-commit install
```

## Running Tests & Verifying Coverage

### 1. Fast Offline Unit Tests
Unit tests run in isolation without external dependencies or containers:
```bash
mvn test -q
```

### 2. Integration Tests (Testcontainers)
Integration tests verify end-to-end storage, distributed locking, and protocol flows against local Testcontainers instances (RustFS for S3, Azurite for Azure Blob):
```bash
mvn verify -q
```
> **Container Runtime**: Integration tests automatically spin up and manage container lifecycles via Testcontainers using either **Podman** or **Docker**. No manual container startup is required. If no container runtime is running, integration tests are gracefully skipped.

### 3. Code Coverage Gate
Verify that modified code meets the required unit test coverage threshold (default: 90% per file):
```bash
python3 scripts/check-coverage.py --per-file-limit 90
```
