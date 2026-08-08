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
    SNAPSHOT_RESTORE
}
