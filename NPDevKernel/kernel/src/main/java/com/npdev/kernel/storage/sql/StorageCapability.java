package com.npdev.kernel.storage.sql;

/**
 * What a storage engine can actually do.
 *
 * <p>This enum is what makes a second (and eventually a non-SQL) engine safe to add. The generator
 * refuses a model at GENERATION time when it needs a capability the chosen engine lacks, naming the
 * engine, the capability and the model element -- rather than letting the app boot and discover the
 * gap as missing data.
 *
 * <p><b>Two rules, both load-bearing.</b>
 * <ul>
 *   <li><b>Never declare a capability whose conformance vector is not green.</b> {@link
 *       SqlDialect#capabilities()} is a promise the generator rejects user models against; a wrong
 *       entry either blocks valid models or accepts impossible ones.</li>
 *   <li><b>Never let a missing capability degrade silently.</b> That is the X0 rule, and the storage
 *       layer is the worst possible place to break it -- a silent no-op looks like success and shows
 *       up as missing data much later. {@link SqlDialect#require(StorageCapability)} is how a site
 *       asks, and conformance vector C1 is what proves it refuses.</li>
 * </ul>
 */
public enum StorageCapability {

    /** Statements can be grouped into an atomic unit that commits or rolls back as one. */
    TRANSACTIONS,

    /**
     * <b>DDL participates in a transaction and can be rolled back.</b>
     *
     * <p>Postgres and SQL Server: yes. H2: no (already recorded as boundary B11). <b>MySQL commits
     * IMPLICITLY on DDL</b> -- breaking the schema engine's rollback assumption in the opposite
     * direction from H2, which is why it is easy to miss. A migration that half-applies and reports
     * success is the worst outcome the storage layer can produce, so the schema engine must consult
     * this rather than assume it.
     */
    DDL_IN_TRANSACTION,

    /** Existing tables can be altered in place (add/rename/retype a column) rather than recreated. */
    SCHEMA_EVOLUTION,

    /** Referential integrity is enforced by the engine, not by application code. */
    FOREIGN_KEYS,

    /** Uniqueness is enforced by the engine, so a concurrent duplicate insert fails rather than lands. */
    UNIQUE_CONSTRAINTS,

    /** Rows from two concepts can be joined by the engine in one round trip. */
    SERVER_SIDE_JOIN,

    /** A multi-stage grouping/aggregation pipeline runs inside the engine. */
    AGGREGATION_PIPELINE,

    /** A row carries a version the engine can compare-and-set against, so a lost update is detected. */
    OPTIMISTIC_LOCKING,

    /** A point-in-time copy of the data can be taken and restored by the engine. */
    SNAPSHOT_RESTORE,

    /**
     * A row-locking read can skip rows a concurrent transaction already holds, instead of blocking
     * on them or erroring.
     *
     * <p><b>R8c (RUN-2): the flow-resume claim's underlying primitive.</b> Two NPDev instances
     * polling the same database for resumable flow instances must partition the eligible rows
     * rather than both attempting to resume the same one -- {@code SELECT ... FOR UPDATE SKIP
     * LOCKED} (or its per-engine equivalent) is what lets N competing pollers each walk away with a
     * disjoint batch in one round trip, with no explicit coordination between them.
     *
     * <p><b>Measured true on all four engines this platform supports today</b> -- unlike {@link
     * #DDL_IN_TRANSACTION}, this is not a capability that splits the matrix:
     * <pre>
     *   Postgres    native FOR UPDATE SKIP LOCKED since 9.5 (2016)
     *   MySQL       native FOR UPDATE SKIP LOCKED since 8.0 GA (this platform already targets 8.4)
     *   H2          native FOR UPDATE SKIP LOCKED since 2.2.220 (2023-07-04, Oracle-style syntax);
     *               this repo already pins 2.2.224, which post-dates it
     *   SQL Server  no SKIP LOCKED keyword, but WITH (..., READPAST) is the documented, long-
     *               standing equivalent -- it skips locked ROWS rather than blocking, exactly the
     *               semantic this capability names, just spelled as a table hint, not a keyword
     * </pre>
     * Declared explicitly anyway, following {@link #DDL_IN_TRANSACTION}'s pattern the R8c card
     * asked for: even though every engine answers yes today, the claim's call site still calls
     * {@link SqlDialect#require(StorageCapability)} before building a skip-locked statement (the X0
     * rule) rather than assuming, so a future fifth engine without this capability fails loudly
     * instead of silently losing the double-resume guard.
     */
    SKIP_LOCKED_READS,

    /**
     * A named mutex the engine scopes to the SESSION that took it, needing <b>no table to exist</b>
     * and released by the engine itself the moment that session's connection dies.
     *
     * <p><b>R9.3: the property that makes a FIRST-EVER boot lockable at all.</b> NPDev's migration
     * mutex has to be held across {@code flyway.migrate()}, and on a virgin database there is
     * nothing to lock -- every table NPDev owns is about to be created by the migration this lock
     * is protecting. Worse, creating one early is not merely useless but actively breaking:
     * self-bootstrapping any table into Flyway's schema ahead of {@code flyway.migrate()} makes
     * Flyway refuse the boot outright with <i>"Found non-empty schema(s) 'public' but no schema
     * history table"</i> (REG-7.2, verified live on {@code simple-user-registry-h2local}). A lock
     * that needs no table is the only kind that can close that window without re-opening REG-7.2.
     *
     * <p><b>Splits the matrix three-to-one</b> -- unlike {@link #SKIP_LOCKED_READS}, this is a
     * capability an engine genuinely lacks, which is exactly why it is a capability and not an
     * assumption:
     * <pre>
     *   Postgres    pg_try_advisory_lock(bigint) / pg_advisory_unlock -- session-scoped
     *   MySQL       GET_LOCK(name, timeout) / RELEASE_LOCK(name)      -- session-scoped
     *   SQL Server  sp_getapplock @LockOwner='Session' / sp_releaseapplock
     *   H2          NONE. H2 has no advisory-lock function of any kind, so the migration mutex
     *               falls back to a row lock held open in its own transaction, in a DEDICATED
     *               schema Flyway does not manage (see MigrationMutex) -- which needs a table, and
     *               therefore has to keep that table out of Flyway's way rather than not need one.
     * </pre>
     * Ask with {@link SqlDialect#supports(StorageCapability)} and branch on the ANSWER, never on
     * the engine name: that is what keeps the fallback one code path instead of three.
     */
    SESSION_ADVISORY_LOCK,

    /**
     * A {@code UNIQUE} index can carry a {@code WHERE} predicate, so the constraint applies only to
     * the rows matching it (e.g. {@code WHERE deleted_at IS NULL}) instead of the whole table.
     *
     * <p><b>R5.4: what makes "unique among live rows only" a real, engine-enforced constraint</b>
     * instead of a JVM-side precheck alone (see {@code ConceptStore#existsUnique}, R5.2/RUN-16) --
     * without it, a soft-deleted row's still-physically-present unique value keeps blocking reuse at
     * the database even after the application-level check has been taught to ignore it.
     * <pre>
     *   Postgres    CREATE UNIQUE INDEX ix ON t (col) WHERE deleted_at IS NULL -- partial index,
     *               documented since 8.0
     *   SQL Server  CREATE UNIQUE INDEX ix ON t (col) WHERE deleted_at IS NULL -- filtered index,
     *               documented since SQL Server 2008; identical syntax to Postgres's
     *   H2          NONE. Empirically confirmed (2026-08-19): H2 2.2.224 raises
     *               "Syntax error in SQL statement" on a WHERE clause attached to CREATE (UNIQUE)
     *               INDEX -- H2 has no partial-index feature at all, at any version this platform
     *               has pinned.
     *   MySQL       NONE. MySQL 8.x has no partial/filtered index syntax; the documented workaround
     *               (a generated column that is NULL for a deleted row, uniquely indexed) is real
     *               engine-specific DDL this platform does not emit today.
     * </pre>
     * Splits the matrix two-to-two, unlike {@link #SKIP_LOCKED_READS}. A concept declaring {@code
     * softDelete: true} still generates correctly on every engine -- {@code SchemaRealizationEmitter}
     * asks {@link SqlDialect#supports(StorageCapability)} and falls back to the same tenant-scoped
     * (non-filtered) unique index a non-soft-delete concept gets on H2/MySQL, documented plainly
     * rather than silently degraded (the X0 rule): on those two engines the DB itself still refuses
     * to reuse a deleted row's unique value, and only the JVM-side {@code existsUnique} precheck
     * (which does exclude deleted rows) can no longer be the whole story for the caller who bypasses
     * it and writes raw SQL.
     */
    PARTIAL_UNIQUE_INDEX
}
