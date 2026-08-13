package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/** Public facade (SER-P6.0): compute the live-database impact of the current model in one call, for the
 *  CLI (REPORT_ONLY) and ControlPanel surfaces. Read-only; never throws for a missing manifest (returns
 *  a NO_CHANGES result). */
public final class SchemaImpactFacade {

    /** The impact report plus the envelope a renderer needs. {@code ackToken} is non-null only when the
     *  verdict is DESTRUCTIVE (the token an operator must supply). {@code surplus} (B3.2) is the
     *  advisory, never-verdict-affecting FK/index surplus classification — {@link ConstraintSurplusReport#EMPTY}
     *  whenever there is no physical database to classify against. */
    public record Result(ImpactReport report, String fromFingerprint, String toFingerprint, String ackToken,
            ConstraintSurplusReport surplus) {
    }

    private SchemaImpactFacade() {
    }

    /** Convenience overload for callers with no {@link CompiledModel} available (e.g. tests) --
     *  equivalent to passing {@code null}, which just skips the REG-39 identity-pack drift check below. */
    public static Result forLiveDatabase(DataSource dataSource) {
        return forLiveDatabase(dataSource, null);
    }

    /**
     * @param compiledModel used only for the REG-39 identity-pack-drift check (see
     *                       {@link IdentityPackDriftItem}); {@code null} skips that check, same as an
     *                       app that doesn't use the identity pack.
     */
    public static Result forLiveDatabase(DataSource dataSource, CompiledModel compiledModel) {
        SchemaLifecycleExecutor.SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
        SchemaDiffItem driftItem = IdentityPackDriftItem.detectOrNull(compiledModel);
        if (manifest == null || !manifest.physicalDatabase()) {
            List<SchemaDiffItem> items = driftItem == null ? List.of() : List.of(driftItem);
            return new Result(ImpactReport.generate(new SchemaDiff(items), dataSource),
                    null, manifest == null ? null : manifest.schemaFingerprint(), null, ConstraintSurplusReport.EMPTY);
        }
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        CurrentSchema scopedCurrent = ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest);
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
        SchemaDiffEngine diffEngine = new SchemaDiffEngine();
        SchemaDiff baseDiff = diffEngine.diff(desired, scopedCurrent);
        SchemaDiff diff = driftItem == null ? baseDiff : withItem(baseDiff, driftItem);
        ImpactReport report = ImpactReport.generate(diff, dataSource);
        // B3.2: the reverse (surplus) direction, from the SAME desired/current pair — no extra query.
        ConstraintSurplusReport surplus = diffEngine.findSurplusConstraints(desired, scopedCurrent);
        String from = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
        String to = manifest.schemaFingerprint();
        String ackToken = null;
        if (report.verdict() == ImpactReport.Verdict.DESTRUCTIVE) {
            // Same residual-token computation the boot refusal uses — SchemaDeltaReport is package-visible here.
            SchemaDeltaReport deltaReport = SchemaDeltaReport.generate(dataSource, manifest);
            ackToken = DestructiveAckToken.compute(to, deltaReport.stableStrings());
        }
        return new Result(report, from, to, ackToken, surplus);
    }

    private static SchemaDiff withItem(SchemaDiff diff, SchemaDiffItem extra) {
        List<SchemaDiffItem> combined = new ArrayList<>(diff.items());
        combined.add(extra);
        return new SchemaDiff(List.copyOf(combined));
    }
}
