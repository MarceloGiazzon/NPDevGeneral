package com.npdev.dsl.v1.schemaevolution;

/**
 * LNCH-1 Phase 4's destructive item vocabulary, moved to the DSL module in Phase 6 (task 6.1's (A)
 * share decision). Exactly four kinds, per {@code docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md} §4.1:
 * {@link DropColumn}, {@link DropTable}, {@link NarrowType}, {@link Unknown}.
 *
 * <p>Two independent producers construct these records for the SAME underlying kind of change:
 * <ul>
 *   <li>{@code com.finalexec.db.SchemaDeltaReport} (RuntimeHost) -- introspects the LIVE database
 *       against the generation-time manifest at boot, itemizing the RESIDUAL diff left over once
 *       the rename/widening steps have already run.</li>
 *   <li>{@code com.npdev.generator.schemaevolution.MigrationPlanEmitter} (the generator) --
 *       diffs two {@code CompiledModel}s directly (no live database) to preview what a future
 *       boot's classification would find.</li>
 * </ul>
 * Because both sides construct the IDENTICAL record types and call the IDENTICAL
 * {@link Item#stableString()} implementations, {@link DestructiveAckToken#compute} produces
 * byte-identical tokens for the same underlying change BY CONSTRUCTION -- not because two
 * independently-maintained string-formatting methods happen to agree today. This is exactly the
 * property §2.3 of the plan requires ("two independent derivations that must agree is the safety
 * mechanism").
 *
 * <p>Every {@link Item#stableString()} is a plain, colon-joined {@code KIND:field:field:...}
 * string built ONLY from that item's own fields -- never from iteration order -- so two callers
 * that discover the same logical item set via different collection/iteration orders still produce
 * the same string. Callers are responsible for their own deterministic ordering of the overall
 * item LIST (see each producer's own sorting logic); {@link DestructiveAckToken#compute} itself
 * additionally sorts the supplied strings lexicographically before hashing, so list order never
 * affects the computed token either way.
 */
public interface SchemaDeltaItem {

    /** The table this item concerns, or {@code ""} if not applicable (only {@link Unknown}). */
    String table();

    /** A stable string form built only from this item's own fields -- never from iteration order --
     * suitable for hashing (via {@link DestructiveAckToken}) and for JSON/log/history-row
     * serialization. */
    String stableString();

    record DropColumn(String table, String column, String sqlType) implements SchemaDeltaItem {
        @Override
        public String stableString() {
            return "DROP_COLUMN:" + table + ":" + column + ":" + (sqlType == null ? "" : sqlType);
        }
    }

    record DropTable(String table, long rowCountAtClassification) implements SchemaDeltaItem {
        @Override
        public String stableString() {
            return "DROP_TABLE:" + table + ":" + rowCountAtClassification;
        }
    }

    /**
     * A shared column whose type changed in a way that is not a safe widening --
     * {@link TypeChangeMatrix#classify(String, String)} returned {@code NARROWING} OR
     * {@code INCOMPARABLE} for the (from -&gt; to) pair. Named {@code NARROW_TYPE} for BOTH cases,
     * per the plan's exact item vocabulary (it lists only four kinds, not five) --
     * {@code INCOMPARABLE} (e.g. a wholesale type-family mismatch such as VARCHAR -&gt; INTEGER)
     * is still, in practice, "a type on this column changed in a way that isn't a safe widening",
     * which is exactly what {@code NARROW_TYPE} communicates to an operator deciding whether to
     * acknowledge it; a separate {@code INCOMPARABLE_TYPE} item kind would be a distinction
     * without a difference for surgical-execution purposes (drop-and-recreate-column is the
     * correct DDL response to both cases identically).
     */
    record NarrowType(String table, String column, String fromType, String toType) implements SchemaDeltaItem {
        @Override
        public String stableString() {
            return "NARROW_TYPE:" + table + ":" + column + ":" + fromType + ":" + toType;
        }
    }

    record Unknown(String description) implements SchemaDeltaItem {
        @Override
        public String table() {
            return "";
        }

        @Override
        public String stableString() {
            return "UNKNOWN:" + description;
        }
    }
}
