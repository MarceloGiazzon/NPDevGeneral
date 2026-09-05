package com.npdev.dsl.v1.ast;

/**
 * B16/B19 (Move 9 A3, {@code docs/ACCEPTED_BOUNDARIES.md}): one typed declaration on a reference
 * field -- {@code filter} constrains the picker's candidate rows (the SAME {@code field == literal}
 * / {@code field != literal} grammar {@code visibleWhen} and {@code query.where} already use, enforced
 * server-side, not decoration); {@code multiSelect} opts the picker into choosing more than one row.
 * Reused verbatim (same two properties) by a band's {@code bandPickers} entry, so an FK field and a
 * band collection declare filtering/multi-select the same way instead of two shapes.
 *
 * <p>REAL_LIFT_PLAN_2026-09-03 package C2 (boundary B16 Step 2, EDIT-18): {@code selectorRef} names
 * a top-level {@code selectors[]} entry (a {@link SelectorAst}) whose {@code columns}/{@code
 * filters}/{@code orderBy}/{@code filter} are adopted wholesale for display/search/order; a local
 * {@code filter} declared alongside {@code selectorRef} AND-composes on top of the selector's own
 * {@code filter} rather than replacing it.
 */
public record FieldPickerAst(String filter, boolean multiSelect, String selectorRef) {
}
