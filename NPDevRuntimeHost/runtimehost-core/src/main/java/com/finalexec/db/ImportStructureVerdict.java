package com.finalexec.db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tri-state pre-import safety gate the data import/export tool's "DB Structure Check" step
 * requires (ListaSementes.txt: "the import have to have a step called DB Estructure Check, the
 * reulst of this check can be: equal / compatible ... / incompatible"). Compares the SOURCE column
 * shape an export's manifest.json captured against the TARGET's live columns at import time.
 *
 * <p>Answers one narrow, practical question -- "will inserting the source's rows, using exactly the
 * columns the source provides, work against this target table" -- which is a different question from
 * {@link SchemaCompatibilityVerdict} (live DB vs. THIS BUILD's compiled model manifest) and does not
 * reuse it: there is no compiled model on either side of a file-based import.
 *
 * <p>Deliberately compares column NAMES and NULLABILITY only, not raw {@code data_type} strings:
 * those strings are not comparable across engines without a portable-type mapping layer (H2's
 * {@code CHARACTER VARYING} vs. Postgres's {@code character varying} vs. SQL Server's {@code varchar}
 * for what is genuinely the same column), and the user-facing spec for this check is about column
 * PRESENCE ("an extra column is different, but is compatible"), not type widening.
 */
final class ImportStructureVerdict {

    enum Verdict { EQUAL, COMPATIBLE, INCOMPATIBLE }

    record ColumnProblem(String column, String reason) {
    }

    record TableVerdict(String table, Verdict verdict, List<ColumnProblem> problems) {
    }

    private ImportStructureVerdict() {
    }

    static TableVerdict compare(String table, List<DataTransferManifest.ColumnMeta> source,
            List<DataTransferManifest.ColumnMeta> target) {
        Map<String, DataTransferManifest.ColumnMeta> targetByName = new HashMap<>();
        for (DataTransferManifest.ColumnMeta column : target) {
            targetByName.put(column.name().toLowerCase(Locale.ROOT), column);
        }
        Map<String, DataTransferManifest.ColumnMeta> sourceByName = new HashMap<>();
        for (DataTransferManifest.ColumnMeta column : source) {
            sourceByName.put(column.name().toLowerCase(Locale.ROOT), column);
        }

        List<ColumnProblem> problems = new ArrayList<>();
        boolean hasExtraTolerableColumn = false;

        for (DataTransferManifest.ColumnMeta sourceColumn : source) {
            if (!targetByName.containsKey(sourceColumn.name().toLowerCase(Locale.ROOT))) {
                problems.add(new ColumnProblem(sourceColumn.name(),
                        "the target table has no column named '" + sourceColumn.name()
                                + "' -- this source column's data cannot be written"));
            }
        }

        for (DataTransferManifest.ColumnMeta targetColumn : target) {
            if (sourceByName.containsKey(targetColumn.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean insertable = targetColumn.nullable() || targetColumn.columnDefault() != null;
            if (insertable) {
                hasExtraTolerableColumn = true;
            } else {
                problems.add(new ColumnProblem(targetColumn.name(),
                        "the target has an extra column '" + targetColumn.name() + "' that is NOT NULL with no "
                                + "default -- an insert that omits it (the source never provided it) will fail"));
            }
        }

        if (!problems.isEmpty()) {
            return new TableVerdict(table, Verdict.INCOMPATIBLE, List.copyOf(problems));
        }
        if (hasExtraTolerableColumn) {
            return new TableVerdict(table, Verdict.COMPATIBLE, List.of());
        }
        return new TableVerdict(table, Verdict.EQUAL, List.of());
    }
}
