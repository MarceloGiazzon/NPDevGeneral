package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;

import javax.sql.DataSource;

/** Public facade (SER-P6.0): compute the live-database impact of the current model in one call, for the
 *  CLI (REPORT_ONLY) and ControlPanel surfaces. Read-only; never throws for a missing manifest (returns
 *  a NO_CHANGES result). */
public final class SchemaImpactFacade {

    /** The impact report plus the envelope a renderer needs. {@code ackToken} is non-null only when the
     *  verdict is DESTRUCTIVE (the token an operator must supply). */
    public record Result(ImpactReport report, String fromFingerprint, String toFingerprint, String ackToken) {
    }

    private SchemaImpactFacade() {
    }

    public static Result forLiveDatabase(DataSource dataSource) {
        SchemaLifecycleExecutor.SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
        if (manifest == null || !manifest.physicalDatabase()) {
            return new Result(ImpactReport.generate(new SchemaDiff(java.util.List.of()), dataSource),
                    null, manifest == null ? null : manifest.schemaFingerprint(), null);
        }
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        ImpactReport report = ImpactReport.generate(diff, dataSource);
        String from = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
        String to = manifest.schemaFingerprint();
        String ackToken = null;
        if (report.verdict() == ImpactReport.Verdict.DESTRUCTIVE) {
            // Same residual-token computation the boot refusal uses — SchemaDeltaReport is package-visible here.
            SchemaDeltaReport deltaReport = SchemaDeltaReport.generate(dataSource, manifest);
            ackToken = DestructiveAckToken.compute(to, deltaReport.stableStrings());
        }
        return new Result(report, from, to, ackToken);
    }
}
