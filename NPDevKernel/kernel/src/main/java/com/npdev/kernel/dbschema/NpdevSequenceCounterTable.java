package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

/**
 * R5.3: the backing store for a model's {@code sequences[]} declarative document-numbering
 * counters ({@code nextNumber('name')} -- see {@code com.npdev.kernel.concepts.
 * ConfiguredConceptGatewaySemanticPolicy#allocateSequenceNumber}). One row per fully-composed
 * partition key ({@code scope_key} -- sequence name, plus a tenant segment when the sequence is
 * {@code scope: "tenant"}, plus any date-bucket its own {@code format} implies; see {@code
 * com.npdev.dsl.v1.expr.SequenceNumberFormat#scopeKeySuffix}); {@code current_value} is the last
 * value allocated for that key, incremented atomically under a real row lock -- see {@code
 * com.finalexec.db.JdbcSequenceAllocator} for the query side.
 */
public final class NpdevSequenceCounterTable {
    public static final String NAME = "npdev_sequence_counter";

    private NpdevSequenceCounterTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("scope_key", TEXT),
                        InternalColumnDefinition.required("current_value", BIGINT),
                        InternalColumnDefinition.required("created_at", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("scope_key"),
                List.of()
        );
    }
}
