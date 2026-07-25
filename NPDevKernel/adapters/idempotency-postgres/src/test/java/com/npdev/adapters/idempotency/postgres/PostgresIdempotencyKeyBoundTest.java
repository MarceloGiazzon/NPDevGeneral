package com.npdev.adapters.idempotency.postgres;

import com.npdev.kernel.capability.IdempotencyKeys;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-36 against the engine whose limit caused it.
 *
 * <p>{@code PostgresIdempotencyStoreTest} -- the only test this module had -- runs H2 in PostgreSQL
 * compatibility mode. H2 does not enforce Postgres's btree index-entry size limit, so it could never
 * have caught this bug, and the {@code idempotency-postgres} module was the one {@code *-postgres}
 * adapter with no dependency on {@code postgres-test-support} at all. That gap is why an oversized key
 * shipped: nothing in the suite ever put one through a real index.</p>
 *
 * <p>Part of GATE-PG (needs Docker), like the rest of the Postgres matrix.</p>
 */
class PostgresIdempotencyKeyBoundTest {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS npdev_idempotency (
                tenant_id TEXT NOT NULL,
                capability TEXT NOT NULL,
                operation TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                created_at_ms BIGINT NOT NULL,
                status TEXT NOT NULL,
                result_json_redacted TEXT,
                error_code TEXT,
                PRIMARY KEY (tenant_id, capability, operation, idempotency_key)
            )
            """;

    private DataSource dataSource;
    private PostgresIdempotencyStore store;

    @BeforeEach
    void setUp() {
        dataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(dataSource, SCHEMA);
        PostgresTestSupport.truncate(dataSource, "npdev_idempotency");
        store = new PostgresIdempotencyStore(dataSource);
    }

    @Test
    void anUnboundedKeyIsGenuinelyRejectedByPostgres() throws SQLException {
        // The control that proves REG-36 was a real defect rather than a theoretical one: writing the
        // raw key straight at the table -- exactly what the store used to do -- must still fail, so the
        // tests below are evidence of the fix and not of a forgiving database.
        //
        // It also pins a correction to REG-36's original write-up, found while building this control.
        // A 100,000-character key of ONE REPEATED CHARACTER inserts happily: Postgres compresses an
        // over-sized index value before giving up on it, and "kkkk..." compresses to almost nothing.
        // The btree limit is reached by SIZE AFTER COMPRESSION, so the trigger is an oversized key that
        // is also incompressible -- a hash, a token, a base64 blob, i.e. exactly what a real
        // idempotency key looks like. Hence the pseudo-random-but-deterministic value here.
        SQLException rejected = assertThrows(SQLException.class, () -> insertRawKey(incompressible(8_000)));

        String message = rejected.getMessage().toLowerCase();
        assertTrue(message.contains("index row") || message.contains("size"),
                "expected a btree index-entry size rejection, got: " + rejected.getMessage());

        // ...and the compressible twin does NOT throw, which is the point of the correction above.
        insertRawKey("k".repeat(100_000));
    }

    /**
     * A deterministic string that pglz cannot shrink -- the shape a real idempotency key has (token,
     * hash, base64 payload) and the one that actually reaches the btree limit.
     */
    private static String incompressible(int length) {
        java.util.Random deterministic = new java.util.Random(20260725L);
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) {
            out.append(Long.toHexString(deterministic.nextLong()));
        }
        return out.substring(0, length);
    }

    @Test
    void anOversizedKeySavesAndIsFoundAgain() {
        // The failure mode this closes: the operation itself SUCCEEDED, then the cache write threw, so
        // the caller was told it failed -- and, nothing having been cached, its retry ran the
        // operation a second time. Idempotency defeated by the record meant to guarantee it.
        String oversized = "k".repeat(100_000);

        store.saveSuccess("tenant-a", "persistence", "save", oversized, "{\"id\":\"u-1\"}", 1000L);

        IdempotencyRecord found = store.find("tenant-a", "persistence", "save", oversized).orElseThrow();
        assertEquals("{\"id\":\"u-1\"}", found.resultJsonRedacted());
        assertTrue(found.idempotencyKey().length() <= IdempotencyKeys.MAX_CHARS);
    }

    @Test
    void twoDistinctOversizedKeysKeepDistinctOutcomes() {
        // The register's acceptance criterion: distinct OUTCOMES, not merely distinct strings.
        String first = "k".repeat(50_000) + "-alpha";
        String second = "k".repeat(50_000) + "-beta";

        store.saveSuccess("tenant-a", "persistence", "save", first, "{\"id\":\"alpha\"}", 1000L);
        store.saveSuccess("tenant-a", "persistence", "save", second, "{\"id\":\"beta\"}", 1000L);

        assertEquals("{\"id\":\"alpha\"}",
                store.find("tenant-a", "persistence", "save", first).orElseThrow().resultJsonRedacted());
        assertEquals("{\"id\":\"beta\"}",
                store.find("tenant-a", "persistence", "save", second).orElseThrow().resultJsonRedacted());
    }

    @Test
    void ordinaryKeysWrittenBeforeThisChangeAreStillFound() {
        // Short keys must stay byte-identical or every idempotency record already in a live database
        // becomes unreachable on upgrade -- which would turn a storage fix into a correctness outage.
        store.saveSuccess("tenant-a", "persistence", "save", "order-42", "{\"id\":\"u-1\"}", 1000L);

        IdempotencyRecord found = store.find("tenant-a", "persistence", "save", "order-42").orElseThrow();
        assertEquals("order-42", found.idempotencyKey());
    }

    private void insertRawKey(String rawKey) throws SQLException {
        String sql = """
                INSERT INTO npdev_idempotency (
                    tenant_id, capability, operation, idempotency_key, created_at_ms, status
                ) VALUES ('tenant-raw', 'persistence', 'save', ?, 1000, 'SUCCESS')
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rawKey);
            statement.executeUpdate();
        }
    }
}
