package com.finalexec.db;

import com.finalexec.db.schemastate.Resolution;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.finalexec.db.schemastate.SafetyClass;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Impact Report (schema-engine rebuild, Phase 6 — the GeneXus IAR equivalent). Wraps a
 * {@link SchemaDiff} with a read-only per-item row-count probe and an overall verdict, so an operator can
 * see the blast radius of an upgrade BEFORE it runs. One engine ({@code SchemaDiff}), one report object,
 * three surfaces (boot refusal, {@code REPORT_ONLY} pre-deploy, ControlPanel — Phases 6.3–6.5).
 *
 * <p><b>Strictly read-only and never-throwing.</b> Every probe is a single {@code SELECT COUNT(*)} with a
 * short statement timeout; a probe that fails or times out degrades that one item to {@code rowsAffected =
 * -1} with a note, and NEVER fails the report. Building the report issues zero DDL and zero writes.
 */
public final class ImpactReport {

    /** Overall assessment, worst-item-wins. */
    public enum Verdict {
        NO_CHANGES,
        SAFE,
        NEEDS_ATTENTION,
        DESTRUCTIVE
    }

    /** One diff item plus its probe result. {@code rowsAffected == -1} means the probe could not run. */
    public record Item(SchemaDiffItem diffItem, long rowsAffected, String probeNote) {
    }

    private static final int PROBE_TIMEOUT_SECONDS = 5;
    private static final Pattern SIZED_CHAR_TYPE =
            Pattern.compile("(?i)(?:VAR)?CHAR(?:ACTER)?(?:\\s+VARYING)?\\s*\\(\\s*(\\d+)\\s*\\)");

    private final Verdict verdict;
    private final List<Item> items;

    private ImpactReport(Verdict verdict, List<Item> items) {
        this.verdict = verdict;
        this.items = items;
    }

    public Verdict verdict() {
        return verdict;
    }

    public List<Item> items() {
        return items;
    }

    /** Build a report from already-probed items, deriving the verdict the same worst-item-wins way
     * {@link #generate} does. No DB — used by the deterministic renderer tests against a fixed fixture. */
    static ImpactReport ofProbedItems(List<Item> probed) {
        Verdict verdict = probed.isEmpty() ? Verdict.NO_CHANGES : Verdict.SAFE;
        for (Item item : probed) {
            verdict = worse(verdict, verdictFor(item.diffItem()));
        }
        return new ImpactReport(verdict, List.copyOf(probed));
    }

    /**
     * Build the report: probe each diff item read-only against {@code dataSource}, then reduce to a
     * verdict (empty diff → NO_CHANGES; any destructive item → DESTRUCTIVE; any NEEDS_BACKFILL/NEEDS_HOOK
     * → NEEDS_ATTENTION; otherwise SAFE). Never throws.
     */
    public static ImpactReport generate(SchemaDiff diff, DataSource dataSource) {
        // SER-P7.4: a read-only index of every hook.json claim on the classpath -- an item this diff
        // contains that a hook claims renders as HOOK_CLAIMED (text: "HOOK: <id>", JSON: the
        // resolution field) instead of counting toward NEEDS_ATTENTION/DESTRUCTIVE. Never throws.
        Map<String, String> claims = loadClaimIndexBestEffort();
        List<Item> reported = new ArrayList<>();
        Verdict verdict = diff.isEmpty() ? Verdict.NO_CHANGES : Verdict.SAFE;
        try (Connection connection = dataSource.getConnection()) {
            for (SchemaDiffItem raw : diff.items()) {
                Item item = probedItem(connection, raw, claims);
                reported.add(item);
                verdict = worse(verdict, verdictFor(item.diffItem()));
            }
        } catch (Throwable ignored) {
            // A connection-level failure must not fail the report: emit the items with unknown counts.
            if (reported.isEmpty()) {
                for (SchemaDiffItem raw : diff.items()) {
                    String hookId = claimHookId(raw, claims);
                    SchemaDiffItem di = hookId == null ? raw : raw.withResolution(Resolution.HOOK_CLAIMED);
                    String note = hookId == null ? "probe unavailable (no connection)"
                            : "HOOK: " + hookId + " (probe unavailable -- no connection)";
                    reported.add(new Item(di, -1L, note));
                    verdict = worse(verdict, verdictFor(di));
                }
            }
        }
        return new ImpactReport(verdict, List.copyOf(reported));
    }

    private static Item probedItem(Connection connection, SchemaDiffItem raw, Map<String, String> claims) {
        String hookId = claimHookId(raw, claims);
        SchemaDiffItem di = hookId == null ? raw : raw.withResolution(Resolution.HOOK_CLAIMED);
        StringBuilder note = new StringBuilder();
        long rows = probe(connection, di, note);
        if (hookId != null) {
            String prefix = "HOOK: " + hookId + (note.length() > 0 ? ". " : "");
            note.insert(0, prefix);
        }
        return new Item(di, rows, note.toString());
    }

    private static Map<String, String> loadClaimIndexBestEffort() {
        try {
            return ConversionHookRunner.loadClaimIndex();
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /** {@code null} unless an UNRESOLVED, otherwise-attention-worthy item (NEEDS_BACKFILL/NEEDS_HOOK/
     *  DESTRUCTIVE_*) is covered by a claim -- a SAFE_* item is never claim-annotated, since a hook has
     *  nothing to do for one. */
    private static String claimHookId(SchemaDiffItem di, Map<String, String> claims) {
        String hookId = claims.get(di.itemKey());
        if (hookId == null || di.resolution() != Resolution.UNRESOLVED || verdictFor(di) == Verdict.SAFE) {
            return null;
        }
        return hookId;
    }

    private static Verdict verdictFor(SchemaDiffItem di) {
        // SER-P7.4 (rule 6, read-only preview): an item a hook claims does not count toward
        // NEEDS_ATTENTION/DESTRUCTIVE -- the verdict/exit-code apply only to UNCLAIMED items.
        if (di.resolution() == Resolution.HOOK_CLAIMED) {
            return Verdict.SAFE;
        }
        if (di.isDestructive()) {
            return Verdict.DESTRUCTIVE;
        }
        if (di.safetyClass() == SafetyClass.NEEDS_BACKFILL || di.safetyClass() == SafetyClass.NEEDS_HOOK) {
            return Verdict.NEEDS_ATTENTION;
        }
        return Verdict.SAFE;
    }

    private static Verdict worse(Verdict a, Verdict b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Exactly one read-only {@code SELECT COUNT(*)} per item type (or 0 for a non-probed safe item),
     * with a {@value #PROBE_TIMEOUT_SECONDS}s timeout. Returns {@code -1} and records a note on any
     * failure/timeout.
     */
    private static long probe(Connection connection, SchemaDiffItem di, StringBuilder note) {
        String table;
        String sql;
        switch (di.safetyClass()) {
            case DESTRUCTIVE_DROP_TABLE -> {
                table = di.table();
                sql = "SELECT COUNT(*) FROM " + safe(table);
            }
            case DESTRUCTIVE_DROP_COLUMN -> {
                sql = "SELECT COUNT(*) FROM " + safe(di.table()) + " WHERE " + safe(di.column()) + " IS NOT NULL";
            }
            case DESTRUCTIVE_NARROW_TYPE -> {
                Matcher matcher = di.after() == null ? null : SIZED_CHAR_TYPE.matcher(di.after());
                if (matcher != null && matcher.find()) {
                    int newSize = Integer.parseInt(matcher.group(1));
                    sql = "SELECT COUNT(*) FROM " + safe(di.table()) + " WHERE LENGTH(" + safe(di.column())
                            + ") > " + newSize;
                } else {
                    // Non-varchar narrowing (numeric precision, type family change): worst-case is every
                    // non-null value, and it needs a human to judge convertibility.
                    note.append("MANUAL_REVIEW: non-character-length narrowing (" + di.before() + " -> "
                            + di.after() + "); worst-case is every non-null value");
                    sql = "SELECT COUNT(*) FROM " + safe(di.table()) + " WHERE " + safe(di.column()) + " IS NOT NULL";
                }
            }
            case NEEDS_BACKFILL, NEEDS_HOOK -> {
                if (di.itemKey().startsWith("TIGHTEN_NOT_NULL:")) {
                    // Existing column being tightened: the rows that would violate NOT NULL are the NULLs.
                    sql = "SELECT COUNT(*) FROM " + safe(di.table()) + " WHERE " + safe(di.column()) + " IS NULL";
                } else {
                    // New required column: every existing row needs a value.
                    sql = "SELECT COUNT(*) FROM " + safe(di.table());
                }
            }
            default -> {
                return 0L; // safe items (additive, relax, widen, rename, create) affect no existing rows
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : -1L;
            }
        } catch (Throwable failure) {
            if (note.length() == 0) {
                note.append("probe failed or timed out: " + failure.getMessage());
            } else {
                note.append("; probe failed or timed out");
            }
            return -1L;
        }
    }

    /** Every use below embeds the result in SQL text, so this is the QUOTED form (STOR-6): the
     * impact probe is where a column named `order` first reaches a real database. */
    private static String safe(String identifier) {
        return SchemaLifecycleExecutor.quotedIdentifier(identifier);
    }
}
