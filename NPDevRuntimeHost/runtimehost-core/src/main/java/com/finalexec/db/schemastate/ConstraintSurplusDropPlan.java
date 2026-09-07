package com.finalexec.db.schemastate;

import com.finalexec.db.schemastate.ConstraintSurplusClassifier.Classification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * STOR-31 (boundary B3, POSTURAL_LIFT_PLAN_2026-09-07.md package P3): turns an operator's itemized
 * drop request into the exact set of {@link Droppable} surplus constraints the platform will drop --
 * or a {@link Refusal} naming WHY a requested name cannot be dropped. Pure, no JDBC.
 *
 * <p><b>The B3 rule, in one line:</b> only a constraint the classifier calls {@code FOREIGN} is
 * droppable, and only when an operator explicitly names it. {@code PLATFORM_DECLARED},
 * {@code IMPLICIT} and {@code UNCLASSIFIABLE} constraints are refused by every path, and a name not
 * found in the live schema at all is refused rather than guessed at. This is the itemized,
 * token-gated operator decision the boundary's residue demanded -- the platform never proposes a
 * drop, it only executes one the operator has looked at and named.
 *
 * <p>Inputs: the {@link ConstraintSurplusReport} produced by
 * {@code SchemaDiffEngine#findSurplusConstraints} (whose {@code surplus} list is FOREIGN by
 * construction and table-scoped, so a drop is never ambiguous about WHICH table), the
 * name-&gt;classification universe {@link ConstraintSurplusClassifier#classifyLiveByName} (so an
 * {@code IMPLICIT} primary-key backing index is refused BY ITS CLASSIFICATION, not merely because it
 * is absent from the surplus list -- the exact failure this boundary exists to prevent), and the
 * operator's requested names. Name matching is case-insensitive (constraint names are
 * engine-generated and casing-unstable across engines).
 */
public final class ConstraintSurplusDropPlan {

    /** One surplus constraint the operator asked for, resolved to the exact live object to drop. */
    public record Droppable(
            String table, String kind, String liveName, List<String> columns, boolean unique, String referencedTable
    ) {
        /** Stable key for token generation and cross-call comparison -- lower-cased so the hash
         *  cannot drift with engine casing. (Named to avoid the dialect-site checker's SQL-IDENTITY
         *  keyword pattern; this is a pure string builder, no SQL.) */
        public String stableKey() {
            return kind + " " + table + "." + (liveName == null ? "" : liveName.toLowerCase(Locale.ROOT));
        }
    }

    /** One requested name the platform refuses to drop, with the full discoverable diagnostic code. */
    public record Refusal(String name, String code) {
    }

    /** The verdict: what would be dropped, and what the operator asked for but cannot have. */
    public record Plan(List<Droppable> droppable, List<Refusal> refused) {

        public static final Plan EMPTY = new Plan(List.of(), List.of());

        public boolean hasRefusals() {
            return !refused.isEmpty();
        }
    }

    private ConstraintSurplusDropPlan() {
    }

    /**
     * @param report       the CURRENT live surplus (FOREIGN-only, table-scoped) -- never a stale or
     *                     caller-supplied list; the caller recomputes it right before planning
     * @param liveByName   every live constraint's classification, keyed by lower-cased name (see
     *                     {@link ConstraintSurplusClassifier#classifyLiveByName})
     * @param requested    the operator's itemized names, in the order they asked
     */
    public static Plan plan(ConstraintSurplusReport report, Map<String, Classification> liveByName,
            List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Plan.EMPTY;
        }
        List<Droppable> droppable = new ArrayList<>();
        List<Refusal> refused = new ArrayList<>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            List<SurplusConstraint> matches = report.surplus().stream()
                    .filter(s -> s.liveName() != null && s.liveName().equalsIgnoreCase(name))
                    .toList();
            if (!matches.isEmpty()) {
                for (SurplusConstraint match : matches) {
                    droppable.add(new Droppable(match.table(), match.kind(), match.liveName(),
                            match.columns(), match.unique(), match.referencedTable()));
                }
                continue;
            }
            Classification classification = liveByName.get(name.toLowerCase(Locale.ROOT));
            if (classification != null && classification != Classification.FOREIGN) {
                refused.add(new Refusal(name,
                        "B3:surplus_not_foreign:" + name + ":" + classification));
            } else {
                refused.add(new Refusal(name, "B3:surplus_not_present:" + name));
            }
        }
        return new Plan(List.copyOf(droppable), List.copyOf(refused));
    }
}