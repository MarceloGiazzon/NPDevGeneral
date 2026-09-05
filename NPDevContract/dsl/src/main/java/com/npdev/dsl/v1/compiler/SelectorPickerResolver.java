package com.npdev.dsl.v1.compiler;

import com.npdev.dsl.v1.ast.SelectorAst;

import java.util.List;
import java.util.Map;

/**
 * REAL_LIFT_PLAN_2026-09-03 package C2 (boundary B16 Step 2, EDIT-18): resolves {@code
 * field.picker.selectorRef} / {@code transaction.bandPickers.<name>.selectorRef} against a
 * top-level {@code selectors[]} entry, shared by both compile-time consumers ({@link
 * ModelCompiler#toCompiledFieldPicker} for a plain FK field, {@link ModelCompiler}'s band-picker
 * compilation for a band collection) so the merge logic exists exactly once -- REG-205's own
 * "eliminate hand-copied duplication" precedent.
 *
 * <p>Display/search/order are adopted WHOLESALE from the referenced selector -- {@code
 * field.picker} has no local display/search/order properties to merge with. {@code filter} is the
 * one property a field may ALSO declare locally: when both are present, the selector's own {@code
 * filter} and the local one AND-compose by simple string concatenation, which is valid because
 * {@link com.npdev.dsl.v1.query.PickerFilterGrammar}'s grammar is already flat-AND -- {@code "a &&
 * b"} parses identically whether written by an author or produced by this concatenation.
 *
 * <p>An unresolvable {@code selectorRef} (naming no real selector) resolves to "nothing from the
 * selector, keep only the local filter" -- this class never reports the error itself; {@code
 * PanelValidation} is the single place a missing selector id becomes an authoring-time diagnostic,
 * matching this codebase's separation of resolution from validation everywhere else in the picker
 * machinery.
 */
final class SelectorPickerResolver {

    private SelectorPickerResolver() {
    }

    record Resolved(List<String> displayFields, List<String> searchFields, List<String> orderBy, String filter) {
    }

    static Resolved resolve(String selectorRef, String localFilter, Map<String, SelectorAst> selectorsByName) {
        if (selectorRef == null || selectorRef.isBlank()) {
            return new Resolved(List.of(), List.of(), List.of(), blankToNull(localFilter));
        }
        SelectorAst selector = selectorsByName.get(selectorRef);
        if (selector == null) {
            return new Resolved(List.of(), List.of(), List.of(), blankToNull(localFilter));
        }
        return new Resolved(
                selector.columns(), selector.filters(), selector.orderBy(),
                mergeFilters(selector.filter(), localFilter));
    }

    private static String mergeFilters(String selectorFilter, String localFilter) {
        boolean hasSelectorFilter = selectorFilter != null && !selectorFilter.isBlank();
        boolean hasLocalFilter = localFilter != null && !localFilter.isBlank();
        if (hasSelectorFilter && hasLocalFilter) {
            return selectorFilter.trim() + " && " + localFilter.trim();
        }
        if (hasSelectorFilter) {
            return selectorFilter;
        }
        return blankToNull(localFilter);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
