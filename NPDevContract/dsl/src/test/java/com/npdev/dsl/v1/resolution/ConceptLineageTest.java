package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P3.3: {@link ConceptLineage#computeAll} -- see that class's own doc for what each field means. */
class ConceptLineageTest {

    @Test
    void nonSpecializingConceptHasNoLineageEntry() throws Exception {
        ModelAst raw = parseJson("""
                {
                  "namespace": "lineage.none", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        Map<String, ConceptLineage> lineage = ConceptLineage.computeAll(raw, new ModelResolver().resolve(raw).modelAst());

        assertFalse(lineage.containsKey("Widget"));
    }

    @Test
    void reportsAddedAndInheritedFields() throws Exception {
        ModelAst raw = parseJson("""
                {
                  "namespace": "lineage.basic", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Invoice", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string" },
                        { "name": "total", "type": "long" } ] },
                    { "name": "MedicalInvoice", "specializes": "Invoice", "fields": [
                        { "name": "doctorId", "type": "uuid" } ] }
                  ]
                }
                """);
        Map<String, ConceptLineage> lineage = ConceptLineage.computeAll(raw, new ModelResolver().resolve(raw).modelAst());

        ConceptLineage medicalInvoice = lineage.get("MedicalInvoice");
        assertEquals("Invoice", medicalInvoice.parent());
        assertEquals(List.of("doctorId"), medicalInvoice.adds());
        assertEquals(Set.of("id", "code", "total"), Set.copyOf(medicalInvoice.inherits()));
        assertTrue(medicalInvoice.changes().isEmpty());
        assertTrue(medicalInvoice.removes().isEmpty(), "fields/invariants/events are add-only -- nothing is ever removed today");
        assertNull(medicalInvoice.parentVersion(), "an app-root base concept has no pack version to report");
    }

    @Test
    void inheritedFieldsAccumulateAcrossAChainOfSpecializations() throws Exception {
        ModelAst raw = parseJson("""
                {
                  "namespace": "lineage.chain", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "A", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "fieldA", "type": "string" } ] },
                    { "name": "B", "specializes": "A", "fields": [
                        { "name": "fieldB", "type": "string" } ] },
                    { "name": "C", "specializes": "B", "fields": [
                        { "name": "fieldC", "type": "string" } ] }
                  ]
                }
                """);
        Map<String, ConceptLineage> lineage = ConceptLineage.computeAll(raw, new ModelResolver().resolve(raw).modelAst());

        ConceptLineage c = lineage.get("C");
        assertEquals("B", c.parent());
        assertEquals(List.of("fieldC"), c.adds());
        assertEquals(Set.of("id", "fieldA", "fieldB"), Set.copyOf(c.inherits()),
                "C's base B is looked up fully resolved, so C inherits everything B itself inherited from A too");
    }

    @Test
    void reportsWhichOverrideAllowedAttributesTheSpecializationDeclaredItsOwn() throws Exception {
        ModelAst raw = parseJson("""
                {
                  "namespace": "lineage.changes", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Order", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "ownerId", "type": "string" } ],
                      "access": { "read": "ownerId == $user.id" } },
                    { "name": "AdminOrder", "specializes": "Order",
                      "fields": [ { "name": "tier", "type": "string" } ],
                      "access": { "read": "true" }, "module": "admin" },
                    { "name": "PlainOrder", "specializes": "Order",
                      "fields": [ { "name": "note", "type": "string" } ] }
                  ]
                }
                """);
        Map<String, ConceptLineage> lineage = ConceptLineage.computeAll(raw, new ModelResolver().resolve(raw).modelAst());

        assertEquals(Set.of("access", "module"), Set.copyOf(lineage.get("AdminOrder").changes()));
        assertTrue(lineage.get("PlainOrder").changes().isEmpty());
    }

    @Test
    void parentVersionAndDigestComeFromTheResolvedBasesPackOrigin(@TempDir Path temp) throws Exception {
        write(temp, "packs/base/pack.json", """
                {
                  "dslVersion": "1.0.0", "pack": "base", "version": "1.0.0",
                  "namespace": "com.npdev.base",
                  "description": "P3.3 conformance fixture: parent version/digest via pack origin.",
                  "concepts": [
                    { "name": "Widget", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);
        Path model = write(temp, "model.json", """
                {
                  "namespace": "lineage.origin", "dslVersion": "1.0.0", "version": "1.0",
                  "packs": [ { "$ref": "packs/base/pack.json" } ],
                  "concepts": [
                    { "name": "CustomWidget", "specializes": "base::Widget",
                      "fields": [ { "name": "sku", "type": "string" } ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst raw = new JsonModelParser().parse(resolvedSource);
        Map<String, ConceptLineage> lineage = ConceptLineage.computeAll(raw, new ModelResolver().resolve(raw).modelAst());

        ConceptLineage customWidget = lineage.entrySet().stream()
                .filter(e -> e.getKey().endsWith("CustomWidget"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("CustomWidget not found among: " + lineage.keySet()));

        assertEquals("base::Widget", customWidget.parent());
        assertEquals("1.0.0", customWidget.parentVersion());
        assertTrue(customWidget.parentDigest() != null && customWidget.parentDigest().startsWith("sha256:"),
                "digest: " + customWidget.parentDigest());
    }

    private static ModelAst parseJson(String json) throws Exception {
        Path temp = Files.createTempFile("npdev-concept-lineage-", ".json");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(temp);
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
