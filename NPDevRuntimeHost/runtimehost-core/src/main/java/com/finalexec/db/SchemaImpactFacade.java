package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.Resolution;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public facade (SER-P6.0): compute the live-database impact of the current model in one call, for the
 *  CLI (REPORT_ONLY) and ControlPanel surfaces. Read-only; never throws for a missing manifest (returns
 *  a NO_CHANGES result). */
public final class SchemaImpactFacade {

    /** The impact report plus the envelope a renderer needs. {@code ackToken} is non-null only when the
     *  verdict is DESTRUCTIVE (the token an operator must supply). {@code surplus} (B3.2) is the
     *  advisory, never-verdict-affecting FK/index surplus classification — {@link ConstraintSurplusReport#EMPTY}
     *  whenever there is no physical database to classify against. {@code renameCandidates} (boundary
     *  lift plan 2026-09-02, package 2.2 / B1) is the same kind of advisory value, empty whenever there
     *  is no physical database. {@code sanctioned} (STOR-33 / B14) is the same kind of advisory value:
     *  every destructive item a conversion hook on the classpath claims, mapped to its hook id --
     *  "what the next boot WILL sanction", visible before it happens; empty whenever there is no
     *  physical database or nothing is claimed. Like surplus and rename candidates it never affects
     *  {@code verdict}. */
    public record Result(ImpactReport report, String fromFingerprint, String toFingerprint, String ackToken,
            ConstraintSurplusReport surplus, List<RenameCandidateScorer.Candidate> renameCandidates,
            List<SanctionedDestruction> sanctioned) {
    }

    private SchemaImpactFacade() {
    }

    /** STOR-33 (boundary B14, package P5): the sanctioned-destruction preview -- every item of
     *  {@code report} that a conversion hook on the classpath claims ({@link Resolution#HOOK_CLAIMED})
     *  and that is destructive ({@link SchemaDiffItem#isDestructive()}), as {@code (stableString,
     *  hookId)} pairs, in report order. Shared by the facade, {@code ImpactReportWriter} (REPORT_ONLY)
     *  and {@code SchemaVerifyMain} so every pre-boot surface renders the identical list. Never
     *  throws: a classpath-scan failure degrades to an empty list. */
    public static List<SanctionedDestruction> sanctionedOf(ImpactReport report) {
        Map<String, String> claims;
        try {
            claims = ConversionHookRunner.loadClaimIndex();
        } catch (Throwable ignored) {
            claims = Map.of();
        }
        List<SanctionedDestruction> out = new ArrayList<>();
        for (ImpactReport.Item item : report.items()) {
            SchemaDiffItem di = item.diffItem();
            if (di.resolution() == Resolution.HOOK_CLAIMED && di.isDestructive()) {
                String hookId = claims.get(di.itemKey());
                if (hookId != null) {
                    out.add(new SanctionedDestruction(di.itemKey(), hookId));
                }
            }
        }
        return out;
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
                    null, manifest == null ? null : manifest.schemaFingerprint(), null, ConstraintSurplusReport.EMPTY,
                    List.of(), List.of());
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
        // Boundary lift plan 2026-09-02, package 2.2 (B1): ranked rename candidates, from the SAME
        // desired/current pair — no extra query, same reasoning as surplus above.
        List<RenameCandidateScorer.Candidate> renameCandidates =
                RenameCandidateAnalysis.compute(report, desired, scopedCurrent);
        String from = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
        String to = manifest.schemaFingerprint();
        String ackToken = null;
        if (report.verdict() == ImpactReport.Verdict.DESTRUCTIVE) {
            // Same residual-token computation the boot refusal uses — SchemaDeltaReport is package-visible here.
            SchemaDeltaReport deltaReport = SchemaDeltaReport.generate(dataSource, manifest);
            ackToken = DestructiveAckToken.compute(to, deltaReport.stableStrings());
        }
        // STOR-33 (B14): what conversion hooks on the classpath would sanction on the next boot.
        List<SanctionedDestruction> sanctioned = sanctionedOf(report);
        return new Result(report, from, to, ackToken, surplus, renameCandidates, sanctioned);
    }

    private static SchemaDiff withItem(SchemaDiff diff, SchemaDiffItem extra) {
        List<SchemaDiffItem> combined = new ArrayList<>(diff.items());
        combined.add(extra);
        return new SchemaDiff(List.copyOf(combined));
    }
}
