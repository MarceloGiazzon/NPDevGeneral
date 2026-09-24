package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportStructureVerdictTest {

    private static DataTransferManifest.ColumnMeta column(String name, boolean nullable, String columnDefault) {
        return new DataTransferManifest.ColumnMeta(name, "VARCHAR", nullable, columnDefault);
    }

    @Test
    void identicalColumnSetIsEqual() {
        List<DataTransferManifest.ColumnMeta> columns = List.of(
                column("id", false, null), column("name", true, null));
        ImportStructureVerdict.TableVerdict verdict = ImportStructureVerdict.compare("widgets", columns, columns);
        assertEquals(ImportStructureVerdict.Verdict.EQUAL, verdict.verdict());
        assertTrue(verdict.problems().isEmpty());
    }

    @Test
    void extraNullableTargetColumnIsCompatible() {
        List<DataTransferManifest.ColumnMeta> source = List.of(column("id", false, null));
        List<DataTransferManifest.ColumnMeta> target = List.of(
                column("id", false, null), column("notes", true, null));
        ImportStructureVerdict.TableVerdict verdict = ImportStructureVerdict.compare("widgets", source, target);
        assertEquals(ImportStructureVerdict.Verdict.COMPATIBLE, verdict.verdict());
        assertTrue(verdict.problems().isEmpty());
    }

    @Test
    void extraDefaultedTargetColumnIsCompatible() {
        List<DataTransferManifest.ColumnMeta> source = List.of(column("id", false, null));
        List<DataTransferManifest.ColumnMeta> target = List.of(
                column("id", false, null), column("created_at", false, "now()"));
        ImportStructureVerdict.TableVerdict verdict = ImportStructureVerdict.compare("widgets", source, target);
        assertEquals(ImportStructureVerdict.Verdict.COMPATIBLE, verdict.verdict());
    }

    @Test
    void missingSourceColumnInTargetIsIncompatible() {
        List<DataTransferManifest.ColumnMeta> source = List.of(column("id", false, null), column("name", true, null));
        List<DataTransferManifest.ColumnMeta> target = List.of(column("id", false, null));
        ImportStructureVerdict.TableVerdict verdict = ImportStructureVerdict.compare("widgets", source, target);
        assertEquals(ImportStructureVerdict.Verdict.INCOMPATIBLE, verdict.verdict());
        assertEquals(1, verdict.problems().size());
        assertEquals("name", verdict.problems().get(0).column());
    }

    @Test
    void extraRequiredTargetColumnWithNoDefaultIsIncompatible() {
        List<DataTransferManifest.ColumnMeta> source = List.of(column("id", false, null));
        List<DataTransferManifest.ColumnMeta> target = List.of(
                column("id", false, null), column("owner_id", false, null));
        ImportStructureVerdict.TableVerdict verdict = ImportStructureVerdict.compare("widgets", source, target);
        assertEquals(ImportStructureVerdict.Verdict.INCOMPATIBLE, verdict.verdict());
        assertEquals("owner_id", verdict.problems().get(0).column());
    }

    @Test
    void columnNameComparisonIsCaseInsensitive() {
        List<DataTransferManifest.ColumnMeta> source = List.of(column("ID", false, null));
        List<DataTransferManifest.ColumnMeta> target = List.of(column("id", false, null));
        ImportStructureVerdict.TableVerdict verdict = ImportStructureVerdict.compare("widgets", source, target);
        assertEquals(ImportStructureVerdict.Verdict.EQUAL, verdict.verdict());
    }
}
