package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.INTEGER;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class InternalSchemaValidatorTest {
    @Test
    void currentRegistryIsValid() {
        InternalSchemaValidationResult result = InternalSchemaValidator.validate(NpdevInternalTables.all());

        assertTrue(result.valid(), "Current registry must be valid: " + result.errors());
    }

    @Test
    void duplicateTableNameFails() {
        InternalTableDefinition table = validTable("npdev_duplicate");

        InternalSchemaValidationResult result = InternalSchemaValidator.validate(List.of(table, table));

        assertInvalidWith(result, "Duplicate internal table name");
    }

    @Test
    void duplicateColumnNameFails() {
        InternalTableDefinition table = new InternalTableDefinition(
                "npdev_duplicate_column",
                List.of(
                        InternalColumnDefinition.required("id", TEXT),
                        InternalColumnDefinition.optional("id", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("id"),
                List.of()
        );

        InternalSchemaValidationResult result = InternalSchemaValidator.validate(List.of(table));

        assertInvalidWith(result, "Duplicate internal column name");
    }

    @Test
    void missingPrimaryKeyColumnFails() {
        InternalTableDefinition table = new InternalTableDefinition(
                "npdev_missing_primary_key_column",
                List.of(InternalColumnDefinition.required("id", TEXT)),
                InternalPrimaryKeyDefinition.of("missing_id"),
                List.of()
        );

        InternalSchemaValidationResult result = InternalSchemaValidator.validate(List.of(table));

        assertInvalidWith(result, "Primary key column is missing");
    }

    @Test
    void missingIndexColumnFails() {
        InternalTableDefinition table = new InternalTableDefinition(
                "npdev_missing_index_column",
                List.of(InternalColumnDefinition.required("id", TEXT)),
                InternalPrimaryKeyDefinition.of("id"),
                List.of(InternalIndexDefinition.index("idx_npdev_missing_index_column", "missing_column"))
        );

        InternalSchemaValidationResult result = InternalSchemaValidator.validate(List.of(table));

        assertInvalidWith(result, "Index column is missing");
    }

    @Test
    void blankNamesAndNullTypeAreRejectedAtConstructionBoundary() {
        assertThrows(IllegalArgumentException.class, () -> validTable(" "));
        assertThrows(IllegalArgumentException.class, () -> InternalColumnDefinition.required(" ", TEXT));
        assertThrows(IllegalArgumentException.class, () -> InternalColumnDefinition.required("id", (InternalColumnType) null));
        assertThrows(IllegalArgumentException.class, () -> InternalColumnDefinition.defaulted("attempt_count", INTEGER, " "));
    }

    @Test
    void nonNpdevTableNameFailsValidation() {
        InternalTableDefinition table = new InternalTableDefinition(
                "external_table",
                List.of(InternalColumnDefinition.required("id", TEXT)),
                InternalPrimaryKeyDefinition.of("id"),
                List.of()
        );

        InternalSchemaValidationResult result = InternalSchemaValidator.validate(List.of(table));

        assertInvalidWith(result, "Internal table name must start with npdev_");
    }

    private static InternalTableDefinition validTable(String name) {
        return new InternalTableDefinition(
                name,
                List.of(InternalColumnDefinition.required("id", TEXT)),
                InternalPrimaryKeyDefinition.of("id"),
                List.of()
        );
    }

    private static void assertInvalidWith(InternalSchemaValidationResult result, String expectedFragment) {
        assertFalse(result.valid(), "Validation must fail");
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(expectedFragment)),
                "Expected validation error containing '" + expectedFragment + "', got: " + result.errors());
    }
}
