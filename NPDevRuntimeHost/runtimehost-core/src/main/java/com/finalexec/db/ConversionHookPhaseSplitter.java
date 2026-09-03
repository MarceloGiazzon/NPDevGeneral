package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift"): mechanically decomposes a hand-written conversion
 * hook's convert SQL into an ordered list of single-statement phases, rewriting every DDL statement it
 * recognizes into its dialect-guarded idempotent form -- reusing the SAME {@code SqlDialect#guardedXxx}
 * surface {@code SchemaRealizationEmitter}/{@code ConversionHookEmitter} already use for GENERATED
 * conversion-hook DDL (ledger STOR-5, "B12's guarantee": generated hooks are already idempotent by
 * construction). This class extends that same guarantee to OPERATOR-authored hooks, which had none.
 *
 * <p>Deliberately narrow, not a SQL parser: {@link SqlDdlGuards}'s own javadoc states the house style
 * this follows -- "refuses anything it does not recognise, rather than parsing SQL." A statement this
 * class cannot render idempotent (a bare {@code DROP COLUMN}, an exotic {@code ALTER}) is reported as
 * BLOCKING rather than guessed at, so {@link ConversionHookRunner} can refuse the boot BEFORE running
 * anything, naming the exact statement -- the same "refuse before running, never a partial apply"
 * discipline STOR-20 already established for the hard-refusal case this extends.
 */
final class ConversionHookPhaseSplitter {

    private ConversionHookPhaseSplitter() {
    }

    enum PhaseKind { DDL, DML }

    /** One statement, ready to run: {@code executableSql} is the DIALECT-GUARDED form for a DDL phase
     *  (or the original statement, unchanged, for a DML phase or a DDL phase whose idempotence needs
     *  no rewrite -- e.g. a redundant {@code SET NOT NULL}). {@code statementHash} is over the
     *  ORIGINAL statement text (before any guard rewrite), so editing convert.sql invalidates old
     *  journal rows even if the rewritten form happens to coincide. */
    record Phase(int ordinal, PhaseKind kind, String executableSql, String statementHash) {
    }

    /** A statement none of the recognized idempotent shapes cover. */
    record Blocked(int ordinal, String statement, String reason) {
    }

    record SplitResult(List<Phase> phases, Blocked blocked) {
        boolean isSplittable() {
            return blocked == null;
        }
    }

    private static final Pattern ADD_COLUMN =
            Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+(\\S+)\\s+ADD\\s+COLUMN\\s+(\\S+)\\s+.*");
    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(\\S+)\\s*\\(.*");
    private static final Pattern CREATE_INDEX =
            Pattern.compile("(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(\\S+)\\s+ON\\s+(\\S+)\\s*\\(.*");
    private static final Pattern NOT_NULL_TOGGLE = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+\\S+\\s+ALTER\\s+COLUMN\\s+\\S+\\s+(SET|DROP)\\s+NOT\\s+NULL\\s*;?\\s*$");
    private static final Pattern ANY_DDL_KEYWORD = Pattern.compile(
            "(?is).*\\b(ALTER\\s+TABLE|DROP\\s+TABLE|CREATE\\s+TABLE|CREATE\\s+(?:UNIQUE\\s+)?INDEX|DROP\\s+INDEX)\\b.*");

    /** Splits and classifies {@code convertSql} against {@code dialect}. Never partially returns a
     *  splittable prefix: the FIRST unrecognized DDL shape blocks the whole result, so a caller never
     *  runs some phases of a hook it could not fully classify. */
    static SplitResult split(String convertSql, SqlDialect dialect) {
        List<String> statements = ConversionHookRunner.splitStatements(convertSql);
        List<Phase> phases = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            String original = statements.get(i);
            Classified classified = classify(original, dialect);
            if (classified == null) {
                return new SplitResult(List.of(), new Blocked(i, original,
                        "matches no recognized idempotent-DDL shape (ADD COLUMN / CREATE TABLE / "
                        + "CREATE [UNIQUE] INDEX / ALTER COLUMN SET|DROP NOT NULL) that this platform "
                        + "knows how to render safe-to-retry"));
            }
            phases.add(new Phase(i, classified.kind(), classified.sql(), sha256Hex(original)));
        }
        return new SplitResult(phases, null);
    }

    private record Classified(PhaseKind kind, String sql) {
    }

    private static Classified classify(String statement, SqlDialect dialect) {
        Matcher addColumn = ADD_COLUMN.matcher(statement);
        if (addColumn.matches()) {
            return new Classified(PhaseKind.DDL,
                    dialect.guardedAddColumn(addColumn.group(1), addColumn.group(2), statement));
        }
        Matcher createTable = CREATE_TABLE.matcher(statement);
        if (createTable.matches()) {
            return new Classified(PhaseKind.DDL, dialect.guardedCreateTable(createTable.group(1), statement));
        }
        Matcher createIndex = CREATE_INDEX.matcher(statement);
        if (createIndex.matches()) {
            return new Classified(PhaseKind.DDL,
                    dialect.guardedCreateIndex(createIndex.group(1), createIndex.group(2), statement));
        }
        if (NOT_NULL_TOGGLE.matcher(statement).matches()) {
            // Re-applying SET/DROP NOT NULL on a column already in that state is a no-op on every
            // engine this platform supports -- no dialect rewrite needed, the plain statement IS
            // idempotent by nature (the same shape ConversionHookEmitter's own generated hooks close
            // every op with).
            return new Classified(PhaseKind.DDL, statement);
        }
        if (ANY_DDL_KEYWORD.matcher(statement).matches()) {
            return null; // a DDL shape recognized as DDL, but not one this class knows how to guard
        }
        return new Classified(PhaseKind.DML, statement);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
