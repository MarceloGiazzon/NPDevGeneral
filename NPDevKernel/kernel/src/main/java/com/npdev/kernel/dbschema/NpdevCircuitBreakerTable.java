package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.INTEGER;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

public final class NpdevCircuitBreakerTable {
    public static final String NAME = "npdev_circuit_breaker";

    private NpdevCircuitBreakerTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("capability", TEXT),
                        InternalColumnDefinition.required("operation", TEXT),
                        InternalColumnDefinition.defaulted("state", TEXT, "'CLOSED'"),
                        InternalColumnDefinition.defaulted("consecutive_failures", INTEGER, "0"),
                        InternalColumnDefinition.defaulted("opened_at_ms", BIGINT, "0"),
                        InternalColumnDefinition.defaulted("last_failure_at_ms", BIGINT, "0"),
                        InternalColumnDefinition.defaulted("half_open_allowed_at_ms", BIGINT, "0"),
                        InternalColumnDefinition.defaulted("half_open_trial_count", INTEGER, "0")
                ),
                InternalPrimaryKeyDefinition.of("tenant_id", "capability", "operation"),
                List.of(InternalIndexDefinition.index("idx_npdev_circuit_breaker_tenant", "tenant_id"))
        );
    }
}
