package com.npdev.kernel.storage.sql;

import java.util.List;
import java.util.Objects;

/**
 * How to perform one "insert, or update if the key is already there" on a given engine -- as a
 * SEQUENCE of statements with the columns each one binds, not a single string.
 *
 * <h2>Why this is a type and not a String (STOR-11)</h2>
 *
 * <p>{@link UpsertStrategy#statementFor} assumes every engine can express the whole operation in one
 * statement whose conflict target is the key the caller named. Three can. MySQL cannot:
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} fires on a clash with <b>any</b> unique index, not the
 * one you keyed on, and there is no syntax to narrow it. Postgres says {@code ON CONFLICT (id)} and
 * therefore raises on a clash with anything else; MySQL quietly treats that clash as an instruction
 * to update.
 *
 * <p>Measured on a real MySQL 8.4 through Tier C vector I3 -- the same request, the same model, a
 * different engine:
 *
 * <pre>
 *   POST /api/concepts/accounts  with an email that already exists (unique: true)
 *     Postgres    409   the unique constraint raises
 *     SQL Server  409
 *     MySQL       200   AND THE OTHER PERSON'S ROW IS OVERWRITTEN WITH THE CALLER'S VALUES
 * </pre>
 *
 * <p>So on MySQL the operation becomes two statements -- UPDATE by key, and INSERT if that matched
 * nothing -- and the clash then arrives as a genuine unique violation, which every layer above
 * already maps to 409. The engine that could name its conflict target keeps its single atomic
 * statement; nothing changes for it.
 *
 * <h2>The execution rule, stated once so no caller invents its own</h2>
 *
 * <p><b>Run the steps in order and STOP after the first one that affects at least one row.</b> A
 * one-step plan is therefore just "run it". A two-step plan is UPDATE-then-INSERT, and the INSERT
 * runs only when the UPDATE matched nothing -- which is exactly "the row is not there yet".
 *
 * <p>Concurrent creates of the same key race, and the loser's INSERT raises a real unique violation.
 * That is the correct outcome rather than a defect: one caller created the row, the other tried to
 * create a row that now exists, and saying so is more honest than silently overwriting -- which is
 * the behaviour this type exists to remove.
 *
 * <h2>Bind order travels with the statement</h2>
 *
 * <p>The same lesson {@link PaginationClause} carries. UPDATE binds values first and its key last
 * ({@code SET a = ?, b = ? WHERE id = ?}); INSERT binds every column in declaration order. A caller
 * that assumed one order for both would write the key into a value column on one of the two
 * statements -- silently, since the types usually match. {@link Step#bindColumns()} makes the
 * mistake unrepresentable: bind exactly those columns, in exactly that order.
 *
 * <p>Deliberately free of {@code java.sql}, like every other type in this package: the kernel has no
 * JDBC imports by design.
 */
public record UpsertPlan(List<Step> steps) {

    /** One statement, plus the columns its placeholders bind, in order. */
    public record Step(String sql, List<String> bindColumns) {
        public Step {
            if (sql == null || sql.isBlank()) {
                throw new IllegalArgumentException("upsert step sql must be non-blank");
            }
            bindColumns = List.copyOf(Objects.requireNonNull(bindColumns, "bindColumns"));
            if (bindColumns.isEmpty()) {
                throw new IllegalArgumentException("an upsert step must bind at least one column");
            }
        }
    }

    public UpsertPlan {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("an upsert plan needs at least one step");
        }
        if (steps.size() > 2) {
            // Not a style rule: the execution contract above is defined for "stop at the first step
            // that affects a row", which has exactly two meaningful shapes. A third step would have
            // no defined meaning and would be executed by guesswork at each call site.
            throw new IllegalArgumentException(
                    "an upsert plan is one statement, or UPDATE-then-INSERT -- got " + steps.size());
        }
    }

    /** The engine expresses the whole operation atomically, keyed on the columns the caller named. */
    public static UpsertPlan single(String sql, List<String> bindColumns) {
        return new UpsertPlan(List.of(new Step(sql, bindColumns)));
    }

    /** UPDATE by key; INSERT only if it matched nothing. See the execution rule above. */
    public static UpsertPlan updateThenInsert(Step update, Step insert) {
        return new UpsertPlan(List.of(
                Objects.requireNonNull(update, "update"),
                Objects.requireNonNull(insert, "insert")));
    }

    /**
     * True when this plan needs the two-statement form -- i.e. the engine's native upsert could not
     * be trusted to react only to the declared key. Reported by {@code npdev capabilities} so the
     * difference is declared rather than discovered.
     */
    public boolean isUpdateThenInsert() {
        return steps.size() == 2;
    }
}
