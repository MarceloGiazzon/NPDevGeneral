package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMP-GAPS-1: the field rename policy, on tables small enough to read. */
class ImportRenamePolicyTest {

    private static final ImportRenamePolicy.Policy DEFAULT = ImportRenamePolicy.defaults();

    private static DbIntrospector.Column col(String name) {
        return new DbIntrospector.Column(name, Types.VARCHAR, "VARCHAR", 20, 0, true, null);
    }

    private static DbIntrospector.ForeignKey fk(String column, String target) {
        return new DbIntrospector.ForeignKey("FK_" + column, target, List.of(column), List.of("Cod"));
    }

    private static DbIntrospector.Table table(String name, List<String> pk, List<DbIntrospector.ForeignKey> fks, String... columns) {
        List<DbIntrospector.Column> cols = new ArrayList<>();
        for (String c : columns) {
            cols.add(col(c));
        }
        return new DbIntrospector.Table(name, cols, pk, fks, List.of());
    }

    private static final Map<String, String> CONCEPTS = Map.of(
            "Armazens", "Armazens", "Entidades", "Entidades", "Movimentos", "Movimentos", "Clientes", "Clientes");

    /** column -> planned field, only for the renames that were applied. */
    private static Map<String, String> applied(Map<String, ImportRenamePolicy.Rename> plan) {
        Map<String, String> out = new LinkedHashMap<>();
        plan.values().stream().filter(ImportRenamePolicy.Rename::applied).forEach(r -> out.put(r.column(), r.field()));
        return out;
    }

    @Test
    void theTablePrefixIsStrippedTheKeyBecomesIdAndTheOneForeignKeyIsNamedAfterItsTarget() {
        var plan = ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(fk("EntCod", "Entidades")),
                "ArmCod", "ArmNome", "ArmAtivo", "ArmLatitude", "EntCod"), CONCEPTS, DEFAULT);
        assertEquals(Map.of("ArmCod", "id", "ArmNome", "nome", "ArmAtivo", "ativo", "ArmLatitude", "latitude",
                "EntCod", "entidadeId"), applied(plan));
        assertEquals(5, plan.size());
        assertEquals("primaryKey", plan.get("ArmCod").rule());
        assertEquals("prefix", plan.get("ArmNome").rule());
        assertEquals("foreignKey", plan.get("EntCod").rule());
        assertEquals("armNome", plan.get("ArmNome").legacyField());
    }

    @Test
    void aPrefixSharedByTooFewColumnsIsNotStripped() {
        var plan = ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(),
                "ArmCod", "ArmNome", "Ativo", "Latitude"), CONCEPTS, DEFAULT);
        assertEquals(Map.of("ArmCod", "id"), applied(plan), "2 of 4 columns share Arm, below minShare 0.6");
    }

    @Test
    void aStrippedNameThatCollidesWithAnotherFieldKeepsItsLegacyName() {
        var plan = ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(),
                "ArmCod", "ArmNome", "ArmAtivo", "ArmCodigo", "Codigo"), CONCEPTS, DEFAULT);
        assertEquals("armCodigo", plan.get("ArmCodigo").field());
        assertEquals("collision", plan.get("ArmCodigo").rule());
        assertFalse(plan.get("ArmCodigo").applied());
        assertFalse(plan.containsKey("Codigo"), "a column no rule touched is not in the plan");
        assertEquals("nome", plan.get("ArmNome").field());
    }

    @Test
    void aStrippedNameStartingWithADigitKeepsItsLegacyName() {
        var plan = ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(),
                "ArmCod", "ArmNome", "ArmAtivo", "Arm1"), CONCEPTS, DEFAULT);
        assertEquals("arm1", plan.get("Arm1").field());
        assertEquals("digit", plan.get("Arm1").rule());
    }

    @Test
    void aStrippedNameThatIsAReservedWordKeepsItsLegacyName() {
        var plan = ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(),
                "ArmCod", "ArmNome", "ArmAtivo", "ArmVersion"), CONCEPTS, DEFAULT);
        assertEquals("armVersion", plan.get("ArmVersion").field());
        assertEquals("reserved", plan.get("ArmVersion").rule());
    }

    @Test
    void twoForeignKeysToTheSameTargetOnlyLoseThePrefix() {
        var plan = ImportRenamePolicy.plan(table("Movimentos", List.of("MovCod"),
                List.of(fk("MovArmOrigem", "Armazens"), fk("MovArmDestino", "Armazens")),
                "MovCod", "MovData", "MovTipo", "MovArmOrigem", "MovArmDestino"), CONCEPTS, DEFAULT);
        assertEquals("armOrigem", plan.get("MovArmOrigem").field());
        assertEquals("armDestino", plan.get("MovArmDestino").field());
        assertEquals("prefix", plan.get("MovArmOrigem").rule());
    }

    @Test
    void aForeignKeyToItsOwnTableIsNotNamedAfterTheTable() {
        var plan = ImportRenamePolicy.plan(table("Entidades", List.of("EntCod"), List.of(fk("EntCodSuperior", "Entidades")),
                "EntCod", "EntNome", "EntTipo", "EntCodSuperior"), CONCEPTS, DEFAULT);
        assertEquals("codSuperior", plan.get("EntCodSuperior").field());
    }

    @Test
    void aNameWithNoWordBoundaryIsLeftAlone() {
        var plan = ImportRenamePolicy.plan(table("Clientes", List.of("CLICOD"), List.of(),
                "CLICOD", "CLINOM", "CLIEMAIL", "CLISALDO"), CONCEPTS, DEFAULT);
        assertEquals(Map.of("CLICOD", "id"), applied(plan));
    }

    @Test
    void aPrefixThatDoesNotAbbreviateTheTableIsNotStripped() {
        var plan = ImportRenamePolicy.plan(table("Users", List.of("Id"), List.of(),
                "Id", "LastName", "LastLogin", "LastIp"), CONCEPTS, DEFAULT);
        assertTrue(applied(plan).isEmpty(), "Last is shared but is not an abbreviation of Users: " + plan);
    }

    @Test
    void aCompositeKeyTableKeepsItsSyntheticIdFreeOfCollisions() {
        var plan = ImportRenamePolicy.plan(table("Pedidos", List.of("PedCod", "PedSeq"), List.of(),
                "PedCod", "PedSeq", "PedId", "PedNome"), CONCEPTS, DEFAULT);
        assertEquals("collision", plan.get("PedId").rule(), "id is taken by the synthetic identity");
        assertEquals("cod", plan.get("PedCod").field());
    }

    @Test
    void disabledPlansNothingAndTheWordsAndSingularAreAsDocumented() {
        assertTrue(ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(),
                "ArmCod", "ArmNome", "ArmAtivo"), CONCEPTS, ImportRenamePolicy.DISABLED).isEmpty());
        assertEquals(List.of("XML", "Data", "2"), ImportRenamePolicy.tokens("XMLData2").stream()
                .map(t -> "XMLData2".substring(t[0], t[1])).toList());
        assertEquals(List.of("CLI", "NOME"), ImportRenamePolicy.tokens("CLI_NOME").stream()
                .map(t -> "CLI_NOME".substring(t[0], t[1])).toList());
        assertEquals("Armazem", ImportRenamePolicy.singular("Armazens", DEFAULT));
        assertEquals("Entidade", ImportRenamePolicy.singular("Entidades", DEFAULT));
        assertEquals("Address", ImportRenamePolicy.singular("Address", DEFAULT));
        assertTrue(ImportRenamePolicy.abbreviates("arm", "ARMAZENS"));
        assertFalse(ImportRenamePolicy.abbreviates("mra", "ARMAZENS"));
    }

    @Test
    void aPolicyFileOfTheUsersOwnIsReadAndAWrongVersionRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policy.json");
        Files.writeString(file, "{\"schemaVersion\":\"npdev-import-rename-policy.v1\",\"fieldPrefix\":{\"enabled\":false},"
                + "\"foreignKey\":{\"enabled\":true,\"pattern\":\"{target}Ref\"},\"primaryKey\":{\"enabled\":false}}");
        var policy = ImportRenamePolicy.load(file);
        var plan = ImportRenamePolicy.plan(table("Armazens", List.of("ArmCod"), List.of(fk("EntCod", "Entidades")),
                "ArmCod", "ArmNome", "ArmAtivo", "EntCod"), CONCEPTS, policy);
        assertEquals(Map.of("EntCod", "entidadesRef"), applied(plan), "no singular rules in this file");
        assertThrows(IllegalArgumentException.class, () -> ImportRenamePolicy.fromJson(
                new ObjectMapper().readTree("{\"schemaVersion\":\"v0\"}"), "x"));
    }
}
