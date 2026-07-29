package com.npdev.dsl.v1.schemaevolution;

/**
 * LNCH-1 Phase 4's destructive item vocabulary, moved to the DSL module in Phase 6 (task 6.1's (A)
 * share decision). Exactly four kinds, per {@code docs/archive/programme-history/LNCH1_SCHEMA_EVOLUTION_PLAN.md} §4.1:
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
 * {@link Item#stableString()} implementations, and every {@code stableString()} uses ONLY
 * fields that are derivable identically from a live database at boot and from a model diff at
 * generation time (no live-only inputs such as row counts participate in the hash --
 * {@link DropTable}'s row count is display metadata, deliberately kept OUT of its stable string),
 * {@link DestructiveAckToken#compute} produces byte-identical tokens for the same underlying
 * change -- not because two independently-maintained string-formatting methods happen to agree
 * today, but because both producers call this exact method and the method uses no live-only
 * inputs. This is exactly the property §2.3 of the plan requires ("two independent derivations
 * that must agree is the safety mechanism").
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

    /** A stable string form built only from this item's own fields -- never from iteration order,
     * and never from a live-only input such as a row count -- suitable for hashing (via
     * {@link DestructiveAckToken}). Two producers computing the same logical item MUST produce the
     * identical stable string; this is the token's safety property. */
    String stableString();

    /** A human-facing form for {@code items_json}, operator log lines, and the plan/report
     * rendering -- MAY include display-only metadata a live boot knows but a generation-time
     * preview does not (e.g. {@link DropTable}'s row count). Defaults to {@link #stableString()};
     * only {@link DropTable} overrides it. Never fed to {@link DestructiveAckToken} -- keeping
     * display metadata here, out of {@link #stableString()}, is what lets the plan-time token match
     * the boot-time token for a concept drop (LNCH-1 remediation F2). */
    default String displayString() {
        return stableString();
    }

    record DropColumn(String table, String column, String sqlType) implements SchemaDeltaItem {
        @Override
        public String stableString() {
            return "DROP_COLUMN:" + table + ":" + column + ":" + (sqlType == null ? "" : sqlType);
        }
    }

    /**
     * A concept whose entire table is being dropped. {@code rowCountAtClassification} is
     * <b>display metadata only</b> -- the operator seeing "~1,240 rows will be lost" in the plan,
     * the report, {@code items_json}, and log lines -- and MUST NOT participate in the hash: the
     * generator has no live database and always constructs this with {@code -1L} ("row count
     * unknown until boot"), so including it would make the plan-time token unable to ever match
     * the executor's boot-time token for a concept drop (LNCH-1 remediation F2). Hence
     * {@link #stableString()} is {@code "DROP_TABLE:" + table} and nothing else.
     */
    record DropTable(String table, long rowCountAtClassification) implements SchemaDeltaItem {
        @Override
        public String stableString() {
            return "DROP_TABLE:" + table;
        }

        /** Includes the row count (display metadata) so operators still see "~N rows will be lost"
         * in {@code items_json} and log lines, even though the count is out of the hash. A
         * generation-time preview carries {@code -1L} here ("row count unknown until boot"). */
        @Override
        public String displayString() {
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
