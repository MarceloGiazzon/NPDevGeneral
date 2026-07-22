package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

/**
 * Append-only log of S0-S8 promotion-stage events (per the Box/Object/Truth model). Each row is one
 * recorded transition attempt — accepted or rejected — never overwritten, so "what stage is this app
 * at, and who approved it" is always reconstructable from history, not a single mutable flag that
 * could be silently overwritten.
 */
public final class NpdevPromotionStateTable {
    public static final String NAME = "npdev_promotion_state";

    private NpdevPromotionStateTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("event_id", TEXT),
                        InternalColumnDefinition.required("ts_ms", BIGINT),
                        InternalColumnDefinition.required("stage", TEXT),
                        InternalColumnDefinition.required("actor_id", TEXT),
                        InternalColumnDefinition.required("roles", TEXT),
                        InternalColumnDefinition.required("evidence", TEXT),
                        InternalColumnDefinition.required("outcome", TEXT),
                        InternalColumnDefinition.required("reason_code", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("event_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_promotion_ts", "ts_ms"),
                        InternalIndexDefinition.index("idx_npdev_promotion_stage_outcome", "stage", "outcome")
                )
        );
    }
}
