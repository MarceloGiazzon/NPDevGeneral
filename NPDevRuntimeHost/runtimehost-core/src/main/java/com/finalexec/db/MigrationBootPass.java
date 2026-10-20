package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.RenameResolution;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.SupportDiagnosticsService;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.finalexec.boundary.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.finalexec.db.SchemaLifecycleExecutor.*;

/**
 * CARVE-2 (2026-10-20): the {@code migrate(Flyway, SchemaManifest)} boot orchestration (dialect pin,
 * ExternallyManaged / Ephemeral / REPORT_ONLY branches, migration claim + lock, beforeMigrate,
 * flyway, afterMigrate), moved verbatim out of {@link SchemaLifecycleExecutor} -- pure mechanical
 * split, no logic change. Per-boot state and the individual passes stay on {@code host}.
 */
final class MigrationBootPass {
    private final SchemaLifecycleExecutor host;

    MigrationBootPass(SchemaLifecycleExecutor host) {
        this.host = host;
    }

    /**
     * Pin {@link SqlDialects#active()} from the engine this app was GENERATED for.
     *
     * <p>Deliberately silent when the manifest names no engine or names {@code InMemory}: neither
     * has SQL, and forcing a dialect there would be inventing an answer. Any other unrecognised
     * value is left alone too -- {@code StorageDialectInitializer} is the component that refuses an
     * unknown engine loudly, and duplicating that refusal here would give two different messages for
     * one condition.
     */
    private static void pinDialectFromManifest(SchemaManifest manifest) {
        String engine = manifest == null ? null : manifest.engine();
        if (engine == null || engine.isBlank()) {
            return;
        }
        String dialectName = switch (engine.trim().toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> "postgres";
            case "h2local", "h2server", "h2" -> "h2";
            case "mysql", "mariadb" -> "mysql";
            case "sqlserver", "mssql" -> "sqlserver";
            default -> null;
        };
        if (dialectName == null) {
            return;
        }
        com.npdev.kernel.storage.sql.SqlDialect dialect;
        try {
            dialect = com.npdev.kernel.storage.sql.SqlDialects.forName(dialectName);
        } catch (RuntimeException unknown) {
            return;
        }
        if (!dialect.name().equals(com.npdev.kernel.storage.sql.SqlDialects.active().name())) {
            System.out.println("NPDev schema lifecycle: pinning SQL dialect to '" + dialect.name()
                    + "' from the manifest engine '" + engine + "' (schema realization runs before "
                    + "StorageDialectInitializer's @PostConstruct is guaranteed to have).");
            com.npdev.kernel.storage.sql.SqlDialects.setActive(dialect);
        }
    }
    void migrate(Flyway flyway, SchemaManifest manifest) {
        // STOR-32 (boundary B7): the per-boot residue key -- every journal row this boot writes (the
        // migration claim, Flyway's migrate, the beforeMigrate/afterMigrate steps) shares it, so a
        // refusal anywhere in the boot can name exactly what already committed.
        host.ensureBootId();
        // PIN THE DIALECT HERE, not only in StorageDialectInitializer.
        //
        // That class's @PostConstruct says it runs "before anything", and nothing enforced it:
        // Spring builds `flywayInitializer` from its own dependencies (dataSource, flyway), which do
        // not include StorageDialectInitializer, so schema realization could -- and on SQL Server
        // did -- run first, while SqlDialects.active() was still the Postgres default.
        //
        // The symptom was maximally misleading. Every guarded DDL statement came out in the H2/
        // Postgres form and SQL Server answered "Incorrect syntax near the keyword 'IF'" -- which
        // reads like a bug in the new guards, and is in fact the guards never being consulted.
        // Measured in CI run 31284112143.
        //
        // This is the entry point of everything that issues DDL, so pinning here cannot be too late,
        // and pinning twice is harmless (setActive is idempotent for the same dialect).
        pinDialectFromManifest(manifest);
        Configuration configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null) {
            flyway.migrate();
            return;
        }
        if (manifest == null || (!manifest.physicalDatabase() && !host.forcePhysicalSchema)) {
            flyway.migrate();
            return;
        }
        // REG-7.1 (D5): ExternallyManaged means NPDev does not own this database's schema -- it must
        // NEVER issue schema DDL against it. Branch here, before any stored-fingerprint read or
        // flyway.migrate() call: this mode does not register/run the schema-realization migrations at
        // all (VERIFIED: the only Flyway location this app is configured with is
        // classpath:db/schema-realization, application.yml's spring.flyway.locations -- so simply
        // never calling flyway.migrate() means Flyway touches nothing, not even its own bookkeeping
        // table). Read-only verification only; deliberately placed ahead of the deprecated-posture
        // NOTICE below since generation-time validation (UserDatabaseDefinitionLoader) already
        // requires allowDestructiveRecreate=false whenever ownership=ExternallyManaged.
        if (manifest.externallyManaged()) {
            host.verifyExternallyManagedSchemaCompatible(dataSource, manifest);
            return;
        }
        // STOR-16: the ephemeral posture, branched BEFORE any fingerprint read, diff computation,
        // impact report, acknowledgment-token consult or PendingSchemaAcknowledgmentStore lookup.
        //
        // The ordering is the feature. `DropAndRecreateOnStructureChange` + allowDestructiveRecreate
        // authorizes itemized column drops and type narrowings ONLY -- a concept drop, or any diff
        // that cannot be executed item by item, still refuses and demands a token (X4.4 / C1). That
        // is correct for a database whose contents matter and useless for a throwaway one, and no
        // combination of the existing flags could express the difference. Placing the branch here
        // means Ephemeral bypasses that machinery BY CONSTRUCTION rather than by each refusal site
        // remembering to check a flag -- one of which would eventually not.
        if (manifest.retiredRecreateOnAppStart()) {
            // ENG-2: never a wipe. This spelling never dropped anything before STOR-16; fall through
            // to the normal non-destructive path rather than refusing to boot an assembled app.
            System.out.println("NPDev schema lifecycle: WARNING -- this app's manifest uses the removed "
                    + "strategy 'RecreateOnAppStart'. It is treated as KeepExistingIfCompatible (what it "
                    + "always did); NO tables are dropped. Regenerate after `npdev migrate db-lifecycle "
                    + "--input <definition dir> --write`.");
        }
        if (manifest.ephemeral()) {
            DestructiveRecreationPass.executeEphemeralWipe(dataSource, manifest);
            // Same follow-up the acknowledged path performs after a full wipe: without clearing the
            // realization history, Flyway sees V1 as already applied and refuses to rebuild the
            // tables that were just dropped, leaving the app running against nothing.
            //
            // ...but only IF there is a history to clear. An Ephemeral app wipes on every boot,
            // including its first, where flyway_schema_history does not exist yet -- and
            // clearSchemaRealizationHistory issues a DELETE against it unconditionally. That threw
            // on a brand-new database, i.e. on the very first start of every ephemeral app, which
            // is a spectacular way to ship a feature. Not fixed by loosening the shared method: its
            // strictness is a real invariant on the acknowledged path, which by construction only
            // runs when a stored fingerprint proves the app has booted before.
            host.clearSchemaRealizationHistoryForEphemeral(dataSource);
            flyway.migrate();
            if (host.forcePhysicalSchema) {
                // See forcePhysicalSchema's own javadoc + MissingTableCreationPass's class javadoc:
                // this manifest has no real V1__ migration (it was generated for InMemory), so
                // flyway.migrate() just did nothing above. Create the business tables straight from
                // the manifest before afterMigrate's own steps (unique constraints, fingerprint write)
                // run against them -- and the platform's own npdev_* tables too, since those normally
                // come from that same absent V1__ migration and afterMigrate only ever hand-creates
                // npdev_schema_metadata itself.
                MissingTableCreationPass.createMissingBusinessTables(dataSource, manifest);
                MissingTableCreationPass.createMissingInternalTables(dataSource);
            }
            host.afterMigrate(dataSource, manifest);
            return;
        }
        // LNCH-1 hardening X4.3: make the deprecated posture visible on EVERY boot, not only on the
        // day it finally destroys something. Deliberately placed here rather than in beforeMigrate:
        // this method runs once per real boot, while beforeMigrate is driven directly by dozens of
        // unit tests that would otherwise flood their captured output.
        if (manifest.destructiveAllowed()) {
            System.out.println("NPDev schema lifecycle: NOTICE -- this app is configured with the deprecated "
                    + "blanket 'destructiveAllowed' posture (schemaLifecycle.strategy="
                    + "DropAndRecreateOnStructureChange + allowDestructiveRecreate=true). Destructive column "
                    + "drops and type narrowings will proceed WITHOUT an itemized acknowledgment token. That is ALL "
                    + "it authorizes: anything that destroys a whole table's worth of data -- a concept drop, or a "
                    + "diff that cannot be executed item by item -- still requires a token. Recommended: switch to "
                    + "strategy=KeepExistingIfCompatible with allowDestructiveRecreate=false and use "
                    + "Build-NpdevApp.ps1 -PlanOnly / -AcknowledgeDestructive -- see docs/SCHEMA_EVOLUTION.md.");
        }
        // SER-P6.4 (Surface 2): REPORT_ONLY mode computes + prints the impact report and exits with a
        // verdict code, WITHOUT any DDL/claim/history write. Read the mode as a JVM system property so no
        // Spring wiring is needed; the -ImpactOnly script passes -Dnpdev.schema.lifecycle.mode=REPORT_ONLY.
        if ("REPORT_ONLY".equalsIgnoreCase(System.getProperty("npdev.schema.lifecycle.mode", "APPLY"))) {
            int code = host.reportOnlyExitCode(dataSource);
            System.out.flush();
            System.exit(code);
        }
        // LNCH-1 remediation R2 (F1): capture the stored fingerprint BEFORE beforeMigrate runs, so we
        // know whether this boot is an upgrade (fingerprint mismatch) independently of whichever
        // beforeMigrate branch ran -- including the surgical-destruction and whole-wipe paths, which
        // previously bypassed required-field enforcement entirely. beforeMigrate never writes the
        // fingerprint (only afterMigrate does, at its very end) and no path drops npdev_schema_metadata,
        // so this read is the true pre-boot value even after a destructive beforeMigrate.
        String storedAtBootStart = readFingerprint(dataSource);
        // B4 (Move 9 A1) / R9.3: take the single migration slot for this boot BEFORE any schema
        // work, so a second instance racing against the same database WAITS instead of interleaving
        // renames/widenings/drops. R9.3 changed what "take" means: this used to refuse on collision
        // (and, on every engine but Postgres, not to lock a virgin database at all), which turned two
        // simultaneous boots into one migrated database and one dead process. It now blocks until the
        // slot is free, on all four engines and including the first-ever boot.
        //
        // freshDatabase is still passed through and still matters, but only for the BOOKKEEPING ROW:
        // writing it means creating npdev_schema_migration_claim, and creating any table in Flyway's
        // schema before flyway.migrate() runs on a virgin schema makes Flyway see a non-empty
        // "public" schema with no history table and refuse outright (REG-7.2). The LOCK itself is
        // taken either way -- see MigrationMutex for how it avoids needing a table at all on three
        // engines, and keeps its table out of Flyway's schema on the fourth.
        boolean freshDatabase = storedAtBootStart == null || storedAtBootStart.isBlank();
        // STOR-28 (B4 lift): a boot with nothing to do (this database is already at THIS build's
        // fingerprint, and Flyway has no failed migration outstanding) takes no lock at all -- a
        // rolling restart where every instance runs the SAME build no longer contends on a mutex that
        // was never going to change anything. MigrationMutex itself is UNCHANGED; this only decides
        // whether SchemaLifecycleExecutor calls it.
        //
        // `!freshDatabase &&` guards EVERY call below, not just an optimization: MigrationPreflight
        // reads npdev_schema_history, which self-bootstraps that table (CREATE TABLE IF NOT EXISTS,
        // unqualified -- Flyway's OWN managed schema) the first time anything queries it. On a
        // genuinely virgin database that happens BEFORE flyway.migrate() ever runs, which is exactly
        // REG-7.2's shape ("Found non-empty schema(s) 'PUBLIC' but no schema history table") --
        // confirmed live: MigrationLockConcurrentBootTest/SchemaLifecycleExecutorMigrationClaimTest's
        // fresh-boot cases failed with FlywayException before this guard existed. Semantically free,
        // not just safe: a fresh database always has real work to do (every table is missing), so
        // nothingToDo would only ever answer false for it anyway -- skipping the CHECK loses nothing.
        MigrationClaimStore.Claim claim = null;
        if (!freshDatabase && MigrationPreflight.nothingToDo(dataSource, manifest)) {
            System.out.println("NPDev schema lifecycle: no schema work for fingerprint "
                    + manifest.schemaFingerprint() + "; skipping the migration lock entirely (STOR-28).");
        } else {
            try {
                // STOR-32 (boundary B7): record the migration claim as the boot's first lifecycle
                // step, BEFORE the lock is taken. GUARDED with !freshDatabase, mirroring
                // MigrationClaimStore.claim's own discipline for its bookkeeping row: started()'s
                // ensureTable would CREATE npdev_boot_residue_journal in Flyway's schema, and on a
                // genuinely virgin database that happens BEFORE flyway.migrate() runs, which is
                // exactly REG-7.2's shape ("Found non-empty schema(s) but no schema history table").
                // A fresh boot has no stored fingerprint and cannot refuse at any of the four named
                // sites (schema-ahead, bond, token), so nothing is lost by not journaling it. A boot
                // that times out holding no claim stays started-not-committed -- a slot that was never
                // won is not "committed work" a retry would re-execute.
                if (!freshDatabase) {
                    BootResidueJournal.started(dataSource, host.bootId, null,
                            BootResidueJournal.LifecycleStep.MIGRATION_CLAIM, null);
                }
                claim = MigrationClaimStore.claim(dataSource, freshDatabase);
                if (!freshDatabase) {
                    BootResidueJournal.committed(dataSource, host.bootId,
                            BootResidueJournal.LifecycleStep.MIGRATION_CLAIM);
                }
            } catch (RuntimeException lockFailure) {
                // Re-check before refusing: the holder may have finished (and this database may now
                // be fully converged) during the very last poll of the wait this timeout closes.
                if (!freshDatabase && MigrationPreflight.nothingToDo(dataSource, manifest)) {
                    System.out.println("NPDev schema lifecycle: the migration lock timed out, but a "
                            + "re-check shows this database is now at fingerprint "
                            + manifest.schemaFingerprint() + " -- the holder finished during the wait; "
                            + "continuing without the lock (STOR-28).");
                } else if (schemaLockDegradesOnTimeout()
                        && ExternalSchemaVerification.findExternalSchemaIncompatibilities(dataSource, manifest).isEmpty()) {
                    // Schema work is still pending and the lock is still unavailable, but the LIVE
                    // schema (whatever it is right now) already serves this build's model -- degrade
                    // rather than refuse. This boot does not migrate; a later boot that wins the lock
                    // still will. Only a genuinely incompatible schema still throws below.
                    String warning = "NPDev schema lifecycle: WARNING -- migration lock timed out and "
                            + "schema work is still pending, but the live schema is already compatible "
                            + "with this build's model; booting WITHOUT applying it (STOR-28, "
                            + "npdev.schema.lock.onTimeout=degrade). " + lockFailure.getMessage();
                    System.out.println(warning);
                    SupportDiagnosticsService.markMigrationDeferred(warning);
                    return;
                } else {
                    // Preserve lockFailure's own message verbatim rather than replacing it with a
                    // generic one: MigrationMutex.waitedOutMessage() names the exact wait budget and
                    // the holder instance/host/claim-time, and NPDevCli's MIGRATION_CLAIM_HELD boot-log
                    // diagnostic greps that phrase verbatim -- discarding it silently demotes a named
                    // diagnostic to "unknown boot failure" (the exact regression this comment exists to
                    // prevent).
                    throw new BoundaryBootException(new BoundaryViolation("B4", "boot",
                            lockFailure.getMessage(), Instant.now()), lockFailure);
                }
            }
        }
        try {
            boolean fingerprintChanged = storedAtBootStart != null && !storedAtBootStart.isBlank()
                    && !storedAtBootStart.equals(manifest.schemaFingerprint());
            DestructiveRecreation recreation = host.beforeMigrate(dataSource, manifest);
            if (recreation.performed()) {
                host.clearSchemaRealizationHistory(dataSource);
            } else {
                // V1's bootstrap SQL is regenerated from the full current model on every generation pass,
                // so its content (and checksum) legitimately changes whenever a column is added even though
                // it must not be re-executed here. repair() reconciles Flyway's recorded checksums with the
                // newly resolved migration content instead of failing validation or re-running V1's CREATE TABLE.
                //
                // REG-106 (live-caught 2026-08-01, Move 10 B2): this used to run ONLY when
                // recreation.safeAdditive() -- i.e. only when the schema FINGERPRINT changed in a
                // known-additive way. But V1's literal SQL text (comments, table/column emission order)
                // can drift across generator runs even when the structural fingerprint does not change
                // at all, so a plain model.json edit with zero concept/table changes still crashed the
                // boot with "Migration checksum mismatch for migration version 1" on the very next
                // regeneration -- flyway.repair() was never called because
                // recreation == DestructiveRecreation.none(). V1 is entirely generated, never
                // hand-authored, so trusting the freshly generated file here (not the historical
                // checksum recorded from whenever this database was last migrated) is correct in the
                // fingerprint-unchanged case for the exact same reason it was already correct in the
                // safeAdditive case -- only widened to cover both.
                flyway.repair();
                System.out.println("NPDev schema lifecycle: flyway.repair() reconciled schema-realization checksums"
                        + (recreation.safeAdditive() ? " for the additive change." : " (no structural change detected)."));
            }
            // STOR-32 (boundary B7): Flyway's own migrate is the last structural step before
            // afterMigrate. Versioned/checksummed, re-running converges by design -- so it is declared
            // idempotent, and a boot that refuses anywhere AFTER it (afterMigrate's backfill,
            // unique-constraint, ownership) can hand the operator a complete picture. GUARDED with
            // !freshDatabase for the SAME REG-7.2 reason as the claim row above: on a first-ever boot
            // flyway_schema_history does not exist yet, so creating the journal table here would make
            // Flyway see a non-empty "public" schema with no history table and refuse outright.
            if (!freshDatabase) {
                BootResidueJournal.started(dataSource, host.bootId, null,
                        BootResidueJournal.LifecycleStep.FLYWAY_MIGRATE, null);
            }
            flyway.migrate();
            if (!freshDatabase) {
                BootResidueJournal.committed(dataSource, host.bootId,
                        BootResidueJournal.LifecycleStep.FLYWAY_MIGRATE);
            }
            host.afterMigrate(dataSource, manifest, storedAtBootStart, fingerprintChanged);
        } finally {
            if (claim != null) {
                MigrationClaimStore.release(dataSource, claim.instanceId());
            }
        }
    }
    /**
     * STOR-28 (B4 lift): {@code -Dnpdev.schema.lock.onTimeout=degrade|refuse}, default {@code
     * degrade}. {@code refuse} restores the pre-STOR-28 behavior exactly (a lock timeout with pending
     * schema work always throws {@code B4:migration_lock_held}, regardless of live-schema
     * compatibility) -- for an operator who would rather a boot fail loudly than ever run against a
     * schema this instance did not itself converge.
     */
    private static boolean schemaLockDegradesOnTimeout() {
        String configured = System.getProperty("npdev.schema.lock.onTimeout");
        return configured == null || configured.isBlank() || !"refuse".equalsIgnoreCase(configured.trim());
    }
}
