package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;

/**
 * Column types for the handful of internal tables the runtime host creates ITSELF, at boot, from
 * inline {@code CREATE TABLE} strings rather than through the generator's {@code internalTables}
 * catalog.
 *
 * <p><b>Why this exists.</b> The catalog tables get their per-engine types from
 * {@code SchemaRealizationEmitter.renderInternalType}, which asks the dialect whether a text column
 * is a payload, a key, or defaulted. These six tables --
 * {@code npdev_schema_migration_claim}, {@code npdev_schema_migration_mark},
 * {@code npdev_schema_pending_ack}, {@code npdev_schema_history}, {@code npdev_schema_metadata}
 * (twice) -- bypass all of that: they are created defensively at runtime, before and around the
 * migration that realizes everything else, so they cannot be part of the schema the migration
 * produces. They spelled {@code TEXT PRIMARY KEY} inline, which no amount of work on the emitter
 * could ever have fixed.
 *
 * <p>Measured in CI run 31284450437, SQL Server 2022:
 *
 * <pre>
 *   Column 'metadata_key' in table 'npdev_schema_metadata' is of a type that is invalid for use as
 *   a key column in an index.
 * </pre>
 *
 * <p>That is STOR-5's shape a fourth time -- the capability is correct in the layer that owns it and
 * is not consulted by the layer that emits. The methods here are deliberately thin: their value is
 * that there is now exactly one approved spelling for these types in the runtime host, so
 * {@code check-dialect-sites.py} can fail a seventh inline {@code TEXT PRIMARY KEY} the moment
 * someone writes it.
 */
final class InternalDdlTypes {

    private InternalDdlTypes() {
    }

    /**
     * The text type for a column in a PRIMARY KEY or an index -- bounded, because MySQL will not
     * index unbounded text without a prefix length and SQL Server cannot index {@code NVARCHAR(MAX)}
     * at all.
     */
    static String keyText() {
        return SqlDialects.active().keyableTextColumnType();
    }

    /**
     * The text type for a column that is only ever read and written, never keyed. Unbounded on every
     * engine -- narrowing a fingerprint, a JSON blob or an operator's note to fit an index it is not
     * in would be data loss to fix a problem that does not exist.
     */
    static String text() {
        return SqlDialects.active().portableColumnType("TEXT");
    }
}
