package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusClassifier;
import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SurplusConstraint;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * STOR-31 (boundary B3, POSTURAL_LIFT_PLAN_2026-09-07.md package P3): the read-side of the surplus
 * drop path -- computes, from the SAME desired/scoped-current pair the impact report already uses
 * (zero extra DB round-trips), the CURRENT live surplus set and the {@code dropToken} that binds it.
 *
 * <p>The token deliberately covers the identity set of every FOREIGN surplus constraint, so the
 * drop endpoint's staleness check is exact: {@code dropToken != prepared().dropToken()} means the
 * live surplus set (or the fingerprint) changed since the token was issued, and the operator's
 * itemized decision no longer corresponds to what is on the database. This is the plan's rule
 * verbatim -- recompute and compare, never trust a submitted list.
 */
public final class SurplusDropSupport {

    /** The verified-live surplus state a preview or a drop resolves against. */
    public record State(
            ConstraintSurplusReport report,
            Map<String, ConstraintSurplusClassifier.Classification> liveByName,
            String dropToken,
            String fingerprint
    ) {
        public boolean hasDroppable() {
            return !report.surplus().isEmpty();
        }
    }

    private SurplusDropSupport() {
    }

    /** Reads the live schema, classifies surplus, and binds a token -- read-only, writes nothing. */
    public static State prepare(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        CurrentSchema scopedCurrent = ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest);
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
        SchemaDiffEngine diffEngine = new SchemaDiffEngine();
        ConstraintSurplusReport report = diffEngine.findSurplusConstraints(desired, scopedCurrent);
        Map<String, ConstraintSurplusClassifier.Classification> liveByName =
                ConstraintSurplusClassifier.classifyLiveByName(desired, scopedCurrent);
        String token = report.surplus().isEmpty() ? null
                : DestructiveAckToken.compute(manifest.schemaFingerprint(), surplusIdentities(report));
        return new State(report, liveByName, token, manifest.schemaFingerprint());
    }

    /** The stable, lower-cased, sorted keys the dropToken hashes — alphabetical, never casing-
     *  dependent, so recomputation across calls is byte-identical. */
    public static List<String> surplusIdentities(ConstraintSurplusReport report) {
        return report.surplus().stream()
                .map(SurplusDropSupport::stableKey)
                .sorted()
                .toList();
    }

    private static String stableKey(SurplusConstraint surplus) {
        return surplus.kind() + " " + surplus.table() + "."
                + (surplus.liveName() == null ? "" : surplus.liveName().toLowerCase(Locale.ROOT));
    }
}