package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * Move 11 W6 (C1, docs/MOVE3_G2_CHECKLISTS.md): a declared, addressable piece of TRANSIENT UI state
 * on an Aggregate Workbench surface -- a record-type toggle and its kind.
 *
 * <p><b>Why this exists.</b> {@code transaction.visibleWhen}'s predicate grammar is
 * {@code $root.<field> == '<literal>'} / {@code !=} -- <i>persisted root fields only</i>. A
 * record-type toggle (centro-trabalho's Recebimento/Expedicao, which decides whether Origem or
 * Destino positions are offered) is not a persisted field of anything; it is a choice the operator
 * makes on the screen. There was simply nothing for the predicate to read, which is why C1 has been
 * {@code cannot-express} since Move 2 G4.
 *
 * <p>So this adds a second resolvable ROOT for the SAME grammar -- {@code $ui.<name>} alongside
 * {@code $root.<field>} -- rather than a second predicate dialect. The workbench renders one control
 * per declared entry, seeded with {@code defaultValue}.
 *
 * <p><b>Presentation-only, and this is load-bearing.</b> UI state never reaches the commit payload
 * and must never gate persistence or authorization -- exactly the rule {@code visibleWhen} already
 * carries: a surface hidden by {@code $ui.*} whose rows exist in the draft still commits them
 * unchanged. Anything stronger would silently delete data through the reconcile path.
 */
public record UiStateControlAst(
        String name,
        String label,
        List<String> values,
        String defaultValue
) {
    public UiStateControlAst {
        values = values == null ? List.of() : List.copyOf(values);
        if ((defaultValue == null || defaultValue.isBlank()) && !values.isEmpty()) {
            defaultValue = values.get(0);
        }
    }
}
