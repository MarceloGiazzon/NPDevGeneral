package com.finalexec.db.schemastate;

import java.util.List;

/**
 * The complete, ordered set of changes between a {@link DesiredSchema} and a {@link CurrentSchema}
 * (schema-engine rebuild, Phase 2), computed once by {@code SchemaDiffEngine}. Ordering is
 * deterministic (table, then column, then kind) so the item list — and any token/report derived from
 * it — is reproducible.
 *
 * @param items the diff items, in deterministic order
 */
public record SchemaDiff(List<SchemaDiffItem> items) {

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Items that would destroy data unless acknowledged or resolved by a hook. */
    public List<SchemaDiffItem> destructiveItems() {
        return items.stream().filter(SchemaDiffItem::isDestructive).toList();
    }

    /** Items an operator can resolve by supplying a conversion hook (Phase 7): destructive items and
     *  new required columns with no automatic backfill. */
    public List<SchemaDiffItem> hookEligibleItems() {
        return items.stream()
                .filter(i -> i.isDestructive() || i.safetyClass() == SafetyClass.NEEDS_HOOK)
                .toList();
    }
}
