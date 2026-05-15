# Postgres Test Support Migration Digest

Checkpoint 1 adds shared Testcontainers support for kernel adapter tests that are named `Postgres*Test`.

The support module starts a real `postgres:15-alpine` container and exposes:

- `PostgresTestSupport.dataSource()`
- `PostgresTestSupport.execute(DataSource, String...)`
- `PostgresTestSupport.truncate(DataSource, String...)`
- `PostgresTestSupport.jdbcUrlForEvidence()`

Migrated adapter tests keep their local schema SQL but now run it against real Postgres through `PGSimpleDataSource`. H2 compatibility mode is no longer used for the targeted Postgres adapter tests.

## Checkpoint 1 - Cross-Platform Testcontainers Support

The shared Postgres Testcontainers support now starts `postgres:15-alpine` with `.withReuse(true)` so compatible local environments can reuse the container between adapter test runs.

Docker host behavior is platform-aware:

- an existing `DOCKER_HOST` environment variable or `docker.host` system property is respected;
- Windows falls back to Docker Desktop's named pipe only when no Docker host is already configured;
- Linux and macOS leave Docker discovery to Testcontainers, which supports Unix socket and CI defaults.

`PostgresTestSupportLinuxCompatibilityTest` covers the host-detection rules without starting Docker.
