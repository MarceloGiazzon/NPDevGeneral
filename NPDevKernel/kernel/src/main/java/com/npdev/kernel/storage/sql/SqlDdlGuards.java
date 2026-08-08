package com.npdev.kernel.storage.sql;

import java.util.Locale;
import java.util.Objects;

/**
 * The small string surgery the four guarded-DDL implementations share -- ledger STOR-5.
 *
 * <h2>Why a shared helper rather than four private copies</h2>
 *
 * <p>Two of the three idioms are native on PostgreSQL, H2 and (for tables) MySQL, so three dialects
 * answer them by inserting the same keyword in the same place. Four private copies of "find CREATE
 * TABLE, put IF NOT EXISTS after it" is how one of them ends up handling {@code CREATE UNIQUE INDEX}
 * and the others do not -- which is exactly the defect family this repo tracks as twin-pair
 * divergence.
 *
 * <h2>Why string surgery at all</h2>
 *
 * <p>The emitter passes the PLAIN statement and each dialect returns the guarded form. The
 * alternative -- have the emitter build both halves and hand the dialect fragments -- puts knowledge
 * of every engine's guard shape back in the emitter, which is precisely what {@code SqlDialect}
 * exists to prevent. The surgery here is deliberately narrow: it inserts or removes ONE keyword
 * sequence at a known position and refuses anything it does not recognise, rather than parsing SQL.
 *
 * <p><b>Refuses rather than guesses.</b> A statement whose shape is not the expected one throws.
 * Silently returning it unchanged would emit an unguarded {@code CREATE TABLE} into a repeatable
 * migration, which fails the whole boot the second time it runs (REG-38, learned on H2) -- a loud
 * failure at generation time is strictly better than that.
 */
public final class SqlDdlGuards {

    private SqlDdlGuards() {
    }

    /**
     * Insert {@code keyword} immediately after {@code prefix}, which must start the statement.
     *
     * <p>Idempotent: a statement that already carries the keyword is returned unchanged, so a caller
     * that guards twice cannot produce {@code IF NOT EXISTS IF NOT EXISTS}.
     */
    public static String insertAfter(String statement, String prefix, String keyword) {
        Objects.requireNonNull(statement, "statement");
        String body = statement.stripLeading();
        String leading = statement.substring(0, statement.length() - body.length());
        String upper = body.toUpperCase(Locale.ROOT);
        String upperPrefix = prefix.toUpperCase(Locale.ROOT);

        int at = upper.indexOf(upperPrefix);
        if (at < 0) {
            throw new IllegalArgumentException(
                    "cannot guard a statement that does not contain '" + prefix + "': "
                    + statement.strip());
        }
        int after = at + prefix.length();
        if (upper.substring(after).stripLeading().startsWith(keyword.toUpperCase(Locale.ROOT))) {
            return statement; // already guarded
        }
        return leading + body.substring(0, after) + " " + keyword + body.substring(after);
    }

    /**
     * Insert {@code keyword} after the {@code INDEX} keyword, whether or not {@code UNIQUE} precedes
     * it.
     *
     * <p>{@code CREATE INDEX} and {@code CREATE UNIQUE INDEX} both occur in the emitted script, and
     * the guard goes after {@code INDEX} in both -- {@code CREATE IF NOT EXISTS UNIQUE INDEX} is not
     * a thing. A single {@code insertAfter(stmt, "CREATE INDEX", ...)} would silently skip every
     * unique index, which is the half-fix that looks complete.
     */
    public static String insertAfterIndexKeyword(String statement, String keyword) {
        return insertAfter(statement, "INDEX", keyword);
    }

    /**
     * Whether {@code statement} is already the output of a guard, so guarding again would nest.
     *
     * <p>The keyword engines get idempotence free -- inserting {@code IF NOT EXISTS} twice is
     * detectable in place. A WRAPPER engine has to ask, and the answer matters: the additive script
     * is a Flyway REPEATABLE migration, and nested guards would be valid SQL that grows harder to
     * read every time someone re-guards, which is how a statement stops being reviewable.
     */
    public static boolean alreadyGuarded(String statement, String marker) {
        return statement != null && statement.stripLeading().toUpperCase(Locale.ROOT)
                .startsWith(marker.toUpperCase(Locale.ROOT));
    }

    /**
     * Remove an {@code IF NOT EXISTS} the caller left in place.
     *
     * <p>Needed by every engine that guards with a wrapper instead of a keyword: the inner statement
     * must be plain, or the guard wraps a statement that is itself a syntax error on that engine.
     */
    public static String stripIfNotExists(String statement) {
        Objects.requireNonNull(statement, "statement");
        return statement.replaceAll("(?i)\\s+IF\\s+NOT\\s+EXISTS\\b", "");
    }

    /**
     * Remove the {@code COLUMN} keyword from {@code ALTER TABLE ... ADD COLUMN c TYPE}.
     *
     * <p><b>T-SQL has no {@code COLUMN} there</b> -- it is {@code ALTER TABLE t ADD c TYPE}. A
     * quieter incompatibility than {@code IF NOT EXISTS}, sitting directly underneath it, and one
     * that would have surfaced as its own CI round the moment the first was fixed.
     */
    public static String stripAddColumnKeyword(String statement) {
        Objects.requireNonNull(statement, "statement");
        return statement.replaceAll("(?i)\\bADD\\s+COLUMN\\b", "ADD");
    }
}
