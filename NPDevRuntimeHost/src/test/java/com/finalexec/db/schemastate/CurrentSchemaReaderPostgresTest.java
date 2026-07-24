package com.finalexec.db.schemastate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Postgres twin of the CurrentSchemaReader golden test (schema-engine rebuild, P1.3) — the cross-engine
 * proof that H2 cannot give (types, catalog case, default formatting, constraint/index catalog shape).
 * Docker-dependent (Testcontainers); runs via GATE-PG (`-PincludePostgresMatrix`), mirroring
 * {@code SchemaLifecycleExecutorPostgresProofMatrixTest}'s reused container.
 */
@Tag("integration")
class CurrentSchemaReaderPostgresTest extends AbstractCurrentSchemaReaderGoldenTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("npdev_current_schema_reader")
            .withUsername("npdev")
            .withPassword("npdev")
            .withReuse(true);

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainerUnlessReused() {
        // withReuse(true): intentionally not stopped here (see the proof-matrix test's note).
    }

    @Override
    protected DataSource dataSource() {
        return new UrlDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
