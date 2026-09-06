package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * B10 (STOR-29, {@code ALL_HITTABLE_LIFT_PLAN_2026-09-05.md} package P6): the realize -> preview ->
 * apply -> verify orchestration, extracted from {@link PromoteMain} so a REST caller gets the exact
 * same one-command arc the CLI already had -- a missing target table stops being something a bare
 * REST caller can hit (residue half (a)), because realization always runs first here. {@link
 * PromoteMain} keeps its own {@code System.exit}-adjacent argument parsing and console formatting;
 * this class has no {@code PrintStream} dependency at all, so it is equally usable from a
 * {@code @RestController} that must return a JSON body, not print lines.
 *
 * <p>Changes no behaviour versus what {@code PromoteMain} already did: {@link #realizeAndPromote} is
 * {@code realizeTargetSchema} + {@code runAfterSchemaRealized} moved here verbatim, and {@link
 * #afterSchemaRealized} is the exact preview/apply/(conditionally) verify sequence {@code
 * PromoteMain.runAfterSchemaRealized} always ran -- verify is skipped, not just reported failed,
 * when apply did not fully match, matching the CLI's own early return.
 */
public final class PromotionArc {

    private PromotionArc() {
    }

    /**
     * @param verifyResult empty when {@code applyResult.allMatched()} is false -- verifying against
     *                      incomplete data was never meaningful, so it is never attempted, exactly
     *                      like {@code PromoteMain.runAfterSchemaRealized}'s own early return.
     */
    public record Result(
            CrossEngineDataPromotion.Preview preview,
            CrossEngineDataPromotion.PromotionResult applyResult,
            Optional<PromotionVerifier.VerificationResult> verifyResult
    ) {
        /** True only when every table copied cleanly AND (when attempted) verified cleanly. */
        public boolean succeeded() {
            return applyResult.allMatched() && verifyResult.map(PromotionVerifier.VerificationResult::allVerified).orElse(false);
        }
    }

    /**
     * Realizes the target schema (Flyway, via {@link SchemaLifecycleExecutor#migrate}) and then runs
     * {@link #afterSchemaRealized}. {@code sourceDialect} must already be the caller's own {@link
     * SqlDialects#active()} -- both the CLI (a fresh probe connection) and a running app's REST
     * endpoint (its own boot-pinned dialect) already satisfy this by construction, matching the
     * identical assumption {@link CrossEngineDataPromotion}/{@link PromotionVerifier} run under
     * throughout. See {@link PromoteMain}'s own class javadoc for why the target's dialect cannot be
     * trusted from the manifest here.
     */
    public static Result realizeAndPromote(
            DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest,
            SqlDialect sourceDialect, SqlDialect targetDialect) {
        realizeTargetSchema(target, manifest, sourceDialect, targetDialect);
        return afterSchemaRealized(source, target, manifest);
    }

    /**
     * The preview/apply/verify sequence alone, assuming the target schema already exists -- exposed
     * separately so a caller that already realized the target (or is re-running against a target a
     * prior {@link #realizeAndPromote} call already realized) is not forced through Flyway again.
     */
    public static Result afterSchemaRealized(
            DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest) {
        CrossEngineDataPromotion.Preview preview = CrossEngineDataPromotion.preview(source, target, manifest);
        CrossEngineDataPromotion.PromotionResult applyResult = CrossEngineDataPromotion.apply(source, target, manifest);
        if (!applyResult.allMatched()) {
            return new Result(preview, applyResult, Optional.empty());
        }
        PromotionVerifier.VerificationResult verifyResult = PromotionVerifier.verify(source, target, manifest);
        return new Result(preview, applyResult, Optional.of(verifyResult));
    }

    /**
     * Moved verbatim from {@code PromoteMain.realizeTargetSchema}: pins {@link SqlDialects#active()}
     * to {@code targetDialect} only for the duration of the Flyway migration (the target's OWN DDL
     * guard syntax needs its own dialect, not the source's), restored to {@code sourceDialect} in
     * every exit path -- including a thrown failure -- so a caller mid-arc is never left with the
     * wrong dialect active for {@link CrossEngineDataPromotion}/{@link PromotionVerifier} afterward.
     */
    static void realizeTargetSchema(
            DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest,
            SqlDialect sourceDialect, SqlDialect targetDialect) {
        try {
            SqlDialects.setActive(targetDialect);
            Flyway flyway = Flyway.configure()
                    .dataSource(target)
                    .locations("classpath:db/schema-realization")
                    .load();
            new SchemaLifecycleExecutor().migrate(flyway, manifest.withEngine(targetDialect.name()));
        } finally {
            SqlDialects.setActive(sourceDialect);
        }
    }
}
