package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackMigrationOpTest {

    @Test
    void renameFieldConceptIsItsOwnConcept() {
        var op = new PackMigrationOp.RenameField("User", "name", "displayName");
        assertEquals("User", op.concept());
    }

    @Test
    void renameConceptReportsItsOldNameAsConcept() {
        var op = new PackMigrationOp.RenameConcept("Client", "Customer");
        assertEquals("Client", op.concept());
    }

    @Test
    void renameFieldRejectsBlankFrom() {
        assertThrows(IllegalArgumentException.class, () -> new PackMigrationOp.RenameField("User", " ", "displayName"));
    }

    @Test
    void renameFieldRejectsNullTo() {
        assertThrows(IllegalArgumentException.class, () -> new PackMigrationOp.RenameField("User", "name", null));
    }

    @Test
    void addFieldRejectsBlankConcept() {
        assertThrows(IllegalArgumentException.class, () -> new PackMigrationOp.AddField("", "notes"));
    }

    @Test
    void dropFieldRejectsBlankField() {
        assertThrows(IllegalArgumentException.class, () -> new PackMigrationOp.DropField("User", ""));
    }
}
