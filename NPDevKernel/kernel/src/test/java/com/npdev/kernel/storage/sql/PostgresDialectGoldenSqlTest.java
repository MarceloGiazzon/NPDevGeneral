package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1's behavioural half: <b>the dialect produces the exact text the call site emitted before
 * extraction.</b>
 *
 * <p><b>Why this test and not only the static baseline.</b>
 * {@code storage/helpers/capture-sql-baseline.py} compares SQL string literals in the SOURCE. That
 * works for a statement that moved unchanged, and it cannot work for one that was SPLIT -- taking
 * {@code LIMIT ? OFFSET ?} out of a literal makes the literal genuinely different, so a source scan
 * reports drift whether or not behaviour changed. The scan was taught to fold the dialect calls back
 * in, which covers the mechanical pagination sites; this file covers the rest, and it is the
 * stronger proof of the two because it checks the ASSEMBLED string rather than the source text.
 *
 * <p><b>Every expected value below is quoted from the pre-extraction source at commit 5680551</b>,
 * with the originating file named. If one of these fails, the dialect changed what the system emits
 * -- fix the dialect, never the expectation.
 */
@DisplayName("S1 golden: PostgresDialect emits exactly what the call sites emitted before")
class PostgresDialectGoldenSqlTest {

    private final SqlDialect postgres = PostgresDialect.INSTANCE;
    private final SqlDialect h2 = H2Dialect.INSTANCE;

    @Nested
    @DisplayName("pagination -- 23 of the 41 sites")
    class Pagination {

        @Test
        @DisplayName("JdbcFlowInstanceStore / JdbcEventStore / JdbcTraceStore: LIMIT ? OFFSET ?")
        void limitOffsetClauseIsUnchanged() {
            assertEquals("LIMIT ? OFFSET ?", postgres.limitOffset().clause());
            assertEquals("LIMIT ? OFFSET ?", h2.limitOffset().clause());
        }

        @Test
        @DisplayName("JdbcFlowInstanceStore.findAllWaiting / ScheduledEventSql.selectDue: LIMIT ?")
        void limitOnlyClauseIsUnchanged() {
            assertEquals("LIMIT ?", postgres.limitOnly().clause());
            assertEquals("LIMIT ?", h2.limitOnly().clause());
        }

        @Test
        @DisplayName("JdbcEventStore.findFirstByEvent / persistence exists(): LIMIT 1")
        void rowLimitIsUnchanged() {
            assertEquals("LIMIT 1", postgres.rowLimit(1));
            assertEquals("LIMIT 1", h2.rowLimit(1));
        }

        @Test
        @DisplayName("a text block keeps its trailing newline, so the assembled statement is identical")
        void paginatedPreservesTheTextBlockShape() {
            String body = """
                    SELECT a
                    FROM t
                    ORDER BY a
                    """;
            assertEquals("""
                    SELECT a
                    FROM t
                    ORDER BY a
                    LIMIT ? OFFSET ?
                    """, postgres.paginated(body));
        }

        @Test
        @DisplayName("parameters are bound in the order the clause declares, not in caller order")
        void valuesFollowTheDeclaredOrder() {
            // Postgres declares (LIMIT, OFFSET). SQL Server will declare (OFFSET, LIMIT), and the
            // whole point of PaginationClause is that this call site does not have to know.
            assertEquals(List.of(20, 40), postgres.limitOffset().values(20, 40));
            assertEquals(List.of(20), postgres.limitOnly().values(20, 40));
        }

        @Test
        @DisplayName("rowLimit(0) is refused -- it reads as 'no rows matched' at every call site")
        void rowLimitRejectsNonPositive() {
            assertThrows(IllegalArgumentException.class, () -> postgres.rowLimit(0));
            assertThrows(IllegalArgumentException.class, () -> postgres.rowLimit(-1));
        }
    }

    @Nested
    @DisplayName("P3: pagination without an explicit order")
    class OrderByRequirement {

        @Test
        @DisplayName("refused, and the message says what to do about it")
        void unorderedPaginationIsRefused() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> postgres.paginated("SELECT a FROM t\n"));
            assertTrue(failure.getMessage().contains("ORDER BY"), failure.getMessage());
            assertTrue(failure.getMessage().contains("postgres"), failure.getMessage());
        }

        @Test
        @DisplayName("refused on EVERY engine, including ones that would accept it")
        void refusalIsUniformAcrossEngines() {
            // The pinned P3 decision. Postgres and MySQL accept an unordered paginated query; SQL
            // Server rejects it outright. Refusing everywhere keeps the engines behaving the same --
            // injecting an order on the engine that needs it would hide the difference AND still
            // return overlapping pages.
            assertThrows(IllegalArgumentException.class, () -> h2.paginated("SELECT a FROM t\n"));
            assertThrows(IllegalArgumentException.class, () -> postgres.limited("SELECT a FROM t\n"));
        }

        @Test
        @DisplayName("an existence probe is NOT required to order -- rowLimited stays open")
        void rowLimitedDoesNotRequireAnOrder() {
            // PostgresPersistenceCapabilityAdapter.exists(): any matching row answers the question.
            assertEquals("select 1 from t where c = ? LIMIT 1\n",
                    postgres.rowLimited("select 1 from t where c = ? ", 1));
        }
    }

    @Nested
    @DisplayName("upsert -- 4 sites, the most divergent construct")
    class Upsert {

        @Test
        @DisplayName("Postgres: INSERT ... ON CONFLICT (id) DO UPDATE SET")
        void postgresUpsertMatchesThePreExtractionShape() {
            // JdbcBusinessConceptStore.upsertSql, pre-extraction:
            //   "INSERT INTO " + table + " (" + join(columns) + ") VALUES (" + placeholders
            //   + ") ON CONFLICT (" + idColumn + ") DO UPDATE SET " + join(updates)
            // where updates = columns except idColumn, each as `col = EXCLUDED.col`.
            assertEquals(
                    "INSERT INTO t (id, a, b) VALUES (?, ?, ?) ON CONFLICT (id) "
                            + "DO UPDATE SET a = EXCLUDED.a, b = EXCLUDED.b",
                    postgres.upsert().statementFor("t", List.of("id"), List.of("id", "a", "b")));
        }

        @Test
        @DisplayName("the id column is excluded from the SET list, case-insensitively")
        void keyColumnIsNotUpdated() {
            String sql = postgres.upsert().statementFor("t", List.of("ID"), List.of("id", "a"));
            assertTrue(sql.contains("DO UPDATE SET a = EXCLUDED.a"), sql);
            assertTrue(!sql.contains("id = EXCLUDED.id"), sql);
        }

        @Test
        @DisplayName("all-key upsert is DO NOTHING, not a DO UPDATE SET with an empty list")
        void allKeyColumnsProducesDoNothing() {
            // An empty SET list is a syntax error. DO NOTHING is the honest statement; emitting a
            // broken one, or silently dropping the ON CONFLICT, are the two worse options.
            assertEquals("INSERT INTO t (id) VALUES (?) ON CONFLICT (id) DO NOTHING",
                    postgres.upsert().statementFor("t", List.of("id"), List.of("id")));
        }

        @Test
        @DisplayName("H2: MERGE INTO t (cols) KEY(id) VALUES (?, ...)")
        void h2UpsertMatchesThePreExtractionShape() {
            // PostgresPersistenceCapabilityAdapter.buildH2UpsertSql and
            // JdbcBusinessConceptStore.upsertSql's h2 branch, pre-extraction.
            assertEquals("MERGE INTO t (id, a, b) KEY(id) VALUES (?, ?, ?)",
                    h2.upsert().statementFor("t", List.of("id"), List.of("id", "a", "b")));
        }

        @Test
        @DisplayName("an upsert with no key column is refused, never silently an INSERT")
        void missingKeyColumnIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> postgres.upsert().statementFor("t", List.of(), List.of("a")));
            assertThrows(IllegalArgumentException.class,
                    () -> h2.upsert().statementFor("t", List.of("id"), List.of()));
        }
    }

    @Nested
    @DisplayName("introspection -- statements that moved whole")
    class Introspection {

        @Test
        @DisplayName("UniqueConstraintPass.constraintExists")
        void constraintExistsSqlIsUnchanged() {
            assertEquals(
                    "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                            + "WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)",
                    postgres.constraintExistsSql());
        }

        @Test
        @DisplayName("StartupValidator's flyway_schema_history probe")
        void tableExistsSqlIsUnchanged() {
            assertEquals(
                    "SELECT COUNT(*) FROM information_schema.tables"
                            + " WHERE LOWER(table_schema) = LOWER(CURRENT_SCHEMA())"
                            + " AND LOWER(table_name) = 'flyway_schema_history'",
                    postgres.tableExistsInCurrentSchemaSql("flyway_schema_history"));
        }

        @Test
        @DisplayName("SchemaRealizationEmitter.addConstraintIfMissing, Postgres arm")
        void guardedConstraintDdlIsUnchanged() {
            assertEquals("""
                    DO $$
                    BEGIN
                      IF NOT EXISTS (
                        SELECT 1
                        FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                        WHERE CONSTRAINT_NAME = 'ux_t_a'
                          AND TABLE_NAME = 't'
                          AND TABLE_SCHEMA = current_schema()
                      ) THEN
                        ALTER TABLE t ADD CONSTRAINT ux_t_a UNIQUE (a);
                      END IF;
                    END $$;
                    """, postgres.guardedConstraintDdl("ux_t_a", "t", "ALTER TABLE t ADD CONSTRAINT ux_t_a UNIQUE (a);"));
        }

        @Test
        @DisplayName("SchemaRealizationEmitter.addConstraintIfMissing, H2 arm (REG-38 idempotence)")
        void h2GuardedConstraintDdlIsUnchanged() {
            assertEquals(
                    "ALTER TABLE t DROP CONSTRAINT IF EXISTS ux_t_a;\n"
                            + "ALTER TABLE t ADD CONSTRAINT ux_t_a UNIQUE (a);\n",
                    h2.guardedConstraintDdl("ux_t_a", "t", "ALTER TABLE t ADD CONSTRAINT ux_t_a UNIQUE (a);"));
        }

        @Test
        @DisplayName("a DDL statement missing its semicolon still gets one, as before")
        void guardedDdlTerminatesTheStatement() {
            assertTrue(h2.guardedConstraintDdl("c", "t", "ALTER TABLE t ADD CONSTRAINT c UNIQUE (a)")
                    .endsWith("ALTER TABLE t ADD CONSTRAINT c UNIQUE (a);\n"));
        }

        @Test
        @DisplayName("SchemaLifecycleExecutor / CurrentSchemaReader system schemas")
        void systemSchemasAreUnchanged() {
            assertEquals(java.util.Set.of("information_schema", "pg_catalog"), postgres.systemSchemas());
            assertEquals(java.util.Set.of("information_schema", "pg_catalog"), h2.systemSchemas());
        }
    }

    @Nested
    @DisplayName("column types")
    class ColumnTypes {

        @Test
        @DisplayName("SchemaRealizationEmitter.renderType, Postgres: the declaration passes through")
        void postgresLeavesDeclaredTypesAlone() {
            // Pre-extraction the H2 narrowing was inside `if (engine == H2_*)`, so Postgres returned
            // the trimmed declaration untouched -- JSON and JSONB are DISTINCT types there and a
            // model that declares one must not silently get the other.
            assertEquals("JSONB", postgres.portableColumnType("JSONB"));
            assertEquals("JSON", postgres.portableColumnType("JSON"));
            assertEquals("TIMESTAMP WITH TIME ZONE", postgres.portableColumnType("TIMESTAMP WITH TIME ZONE"));
            assertEquals("VARCHAR(120)", postgres.portableColumnType("VARCHAR(120)"));
            assertEquals("UUID", postgres.portableColumnType("UUID"));
        }

        @Test
        @DisplayName("SchemaRealizationEmitter.renderType / ConversionHookEmitter, H2: JSONB narrows to JSON")
        void h2NarrowsJsonb() {
            assertEquals("JSON", h2.portableColumnType("JSONB"));
            assertEquals("JSON", h2.portableColumnType("JSON"));
            assertEquals("TIMESTAMP WITH TIME ZONE", h2.portableColumnType("TIMESTAMP WITH TIME ZONE"));
            assertEquals("VARCHAR(120)", h2.portableColumnType("VARCHAR(120)"));
        }

        @Test
        @DisplayName("JdbcBusinessConceptStore / SchemaDropSnapshotWriter / CrossEngineDataPromotion")
        void jsonColumnTypeTestAcceptsBothSpellings() {
            // All three sites tested `"JSON".equalsIgnoreCase(t) || "JSONB".equalsIgnoreCase(t)`.
            for (SqlDialect dialect : List.of(postgres, h2)) {
                assertTrue(dialect.isJsonColumnType("JSON"));
                assertTrue(dialect.isJsonColumnType("JSONB"));
                assertTrue(dialect.isJsonColumnType("jsonb"));
                assertTrue(!dialect.isJsonColumnType("VARCHAR"));
                assertTrue(!dialect.isJsonColumnType(null));
            }
        }
    }
}
