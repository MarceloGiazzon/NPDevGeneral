package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): a modal row picker offered for a band collection,
 * backed by a Selection {@code panel} (e.g. the GeneXus "Seleciona Ruas" pattern). Retires the
 * untyped {@code transaction.metadata.bandPickers} map entry shape.
 *
 * <p>B16/B19 (Move 9 A3, {@code docs/ACCEPTED_BOUNDARIES.md}): {@code filter}/{@code multiSelect} are
 * the SAME two properties {@link FieldPickerAst} declares on a plain FK field -- one picker shape
 * for both surfaces, instead of a band needing a whole separate named {@code panel} just to filter.
 * {@code panel} is no longer required: a band picker with only {@code filter}/{@code multiSelect}
 * targets its own collection's concept directly (the same reference-lookup endpoint an FK field's
 * picker already uses), rather than a hand-authored Selection panel.
 */
public record WorkbenchBandPickerAst(String panel, String label, List<String> columns, String filter, boolean multiSelect) {
    public WorkbenchBandPickerAst {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public WorkbenchBandPickerAst(String panel, String label, List<String> columns) {
        this(panel, label, columns, null, false);
    }
}
