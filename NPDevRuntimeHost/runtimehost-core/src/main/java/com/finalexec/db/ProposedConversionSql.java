package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema-engine rebuild, Phase 8 (SER-P8.1): for a convertible {@code DESTRUCTIVE_NARROW_TYPE} item,
 * drafts the copy-convert SQL pattern an operator can paste straight into a Phase 7 conversion hook.
 * NPDev never auto-runs this -- a proposal is always the operator's own decision to adopt (that
 * discipline, contrasted with GeneXus auto-running its reorganization conversions, is the point).
 * Pure function: no DB, no clock, deterministic; called from {@link ImpactReportJson} and
 * {@link ImpactReportText} for every item, cheaply, on every render.
 *
 * <p>The copy-convert pattern:
 * <pre>
 * ALTER TABLE t ADD COLUMN col__new &lt;newtype&gt;;
 * UPDATE t SET col__new = SUBSTRING(col, 1, &lt;n&gt;);       -- a sized-char narrowing: truncate, don't error
 *   -- or: UPDATE t SET col__new = CAST(col AS &lt;newtype&gt;); -- everything else NARROWING
 * ALTER TABLE t DROP COLUMN col;
 * ALTER TABLE t RENAME COLUMN col__new TO col;
 * </pre>
 * plus a suggested {@code verifySql} (paste as the hook's {@code verifySql}, {@code verifyExpect: 0}):
 * {@code SELECT COUNT(*) FROM t WHERE col IS NOT NULL AND col__new IS NULL} -- run BEFORE the DROP, so
 * it can still see both columns.
 *
 * <p><b>Engine note:</b> H2 and Postgres share this exact syntax (ADD/DROP/RENAME COLUMN,
 * {@code SUBSTRING(str, pos, len)}, {@code CAST(expr AS type)}) for every case this class covers, so
 * there is nothing to branch on per-engine today -- {@link #convertExpression} is the one seam a future
 * per-engine divergence would go through, without touching either renderer.
 *
 * <p><b>Non-goal:</b> an {@code INCOMPARABLE} type-family change (e.g. {@code VARCHAR -> INTEGER}) has
 * no safe generic copy-convert expression -- {@link #forNarrowing} returns {@code null} for those, and
 * the caller shows "no safe automatic conversion -- write a custom hook" instead of a draft.
 */
public final class ProposedConversionSql {

    private static final Pattern SIZED_CHAR_TYPE =
            Pattern.compile("(?i)(?:VAR)?CHAR(?:ACTER)?(?:\\s+VARYING)?\\s*\\(\\s*(\\d+)\\s*\\)");

    /** {@code sql} is the full multi-statement DDL/DML script (paste as a hook's {@code convert.sql});
     *  {@code verifySql} is the suggested post-conversion check. */
    public record Proposal(String sql, String verifySql) {
    }

    private ProposedConversionSql() {
    }

    /** {@code null} when there is no safe automatic conversion (an INCOMPARABLE type-family change, a
     *  same-family no-op, or the item isn't a narrowing at all). */
    public static Proposal forNarrowing(SchemaDiffItem item) {
        if (item.safetyClass() != SafetyClass.DESTRUCTIVE_NARROW_TYPE) {
            return null;
        }
        String table = item.table();
        String column = item.column();
        String before = item.before();
        String after = item.after();
        if (table == null || column == null || before == null || after == null) {
            return null;
        }
        if (TypeChangeMatrix.classify(before, after) != TypeChangeMatrix.Classification.NARROWING) {
            return null;
        }
        String tempColumn = column + "__new";
        String convertExpression = convertExpression(column, after);
        String sql = "ALTER TABLE " + table + " ADD COLUMN " + tempColumn + " " + after + ";\n"
                + "UPDATE " + table + " SET " + tempColumn + " = " + convertExpression + ";\n"
                + "ALTER TABLE " + table + " DROP COLUMN " + column + ";\n"
                + "ALTER TABLE " + table + " RENAME COLUMN " + tempColumn + " TO " + column + ";";
        String verifySql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NOT NULL AND "
                + tempColumn + " IS NULL";
        return new Proposal(sql, verifySql);
    }

    private static String convertExpression(String column, String afterType) {
        Matcher matcher = SIZED_CHAR_TYPE.matcher(afterType);
        if (matcher.find()) {
            return "SUBSTRING(" + column + ", 1, " + matcher.group(1) + ")";
        }
        return "CAST(" + column + " AS " + afterType + ")";
    }
}
