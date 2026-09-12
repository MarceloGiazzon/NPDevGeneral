package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoxManifestEmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsOneBoxPerPersistedConceptWithFieldsBondsAndAdminFlag() throws Exception {
        CompiledField customerId = new CompiledField(
                "customerId", "string", "String", false, false, false, List.of(), "Customer");
        CompiledConcept customer = new CompiledConcept(
                "Customer", "Customer", "customers",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        customerId
                )
        );
        CompiledConcept identityUser = new CompiledConcept(
                "identity::User", "IdentityUser", "identity_users",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(
                order.getName(), order,
                customer.getName(), customer,
                identityUser.getName(), identityUser
        ));

        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());
        new BoxManifestEmitter().emit(model, writer);

        JsonNode root = new ObjectMapper().readTree(
                tempDir.resolve(BoxManifestEmitter.RELATIVE_PATH).toFile());
        JsonNode boxes = root.path("boxes");
        assertEquals(3, boxes.size());

        JsonNode orderBox = findBox(boxes, "Order");
        assertEquals("orders", orderBox.path("table").asText());
        assertEquals(3, orderBox.path("fieldCount").asInt());
        assertEquals(1, orderBox.path("bondCount").asInt());
        assertFalse(orderBox.path("admin").asBoolean());
        assertEquals("T2_GENERATED", orderBox.path("truthLevel").asText());

        JsonNode identityBox = findBox(boxes, "identity::User");
        assertTrue(identityBox.path("admin").asBoolean());
    }

    @Test
    void boxManifestContentIsDeterministicAcrossTwoRuns() throws Exception {
        CompiledConcept concept = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(concept.getName(), concept));

        Path outOne = tempDir.resolve("one");
        Path outTwo = tempDir.resolve("two");
        new BoxManifestEmitter().emit(model, new GeneratedSourceWriter(outOne, new RegenerationPolicy()));
        new BoxManifestEmitter().emit(model, new GeneratedSourceWriter(outTwo, new RegenerationPolicy()));

        assertEquals(
                Files.readString(outOne.resolve(BoxManifestEmitter.RELATIVE_PATH)),
                Files.readString(outTwo.resolve(BoxManifestEmitter.RELATIVE_PATH))
        );
    }

    /**
     * P3.3: the lineage-aware overload attaches a {@code specializes} entry to a box for a
     * specialized concept, computed from the model SOURCE (the compiled box entries themselves are
     * built the same way as every other test in this class -- hand-constructed, decoupled from the
     * model file, matched only by concept name -- since lineage computation and box construction are
     * independent steps that meet by name).
     */
    @Test
    void attachesSpecializationLineageWhenAModelSourceIsInScope() throws Exception {
        CompiledConcept medicalInvoice = new CompiledConcept(
                "MedicalInvoice", "MedicalInvoice", "medical_invoices",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("code", "string", "String", false, true, false),
                        new CompiledField("doctorId", "uuid", "java.util.UUID", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(medicalInvoice.getName(), medicalInvoice));

        Path modelSourcePath = tempDir.resolve("model.json");
        Files.writeString(modelSourcePath, """
                {
                  "namespace": "box.lineage", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Invoice", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string" } ] },
                    { "name": "MedicalInvoice", "specializes": "Invoice", "fields": [
                        { "name": "doctorId", "type": "uuid" } ] }
                  ]
                }
                """);

        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir.resolve("out"), new RegenerationPolicy());
        new BoxManifestEmitter().emit(model, writer, null, modelSourcePath);

        JsonNode root = new ObjectMapper().readTree(
                tempDir.resolve("out").resolve(BoxManifestEmitter.RELATIVE_PATH).toFile());
        JsonNode specializes = findBox(root.path("boxes"), "MedicalInvoice").path("specializes");

        assertEquals("Invoice", specializes.path("parent").asText());
        assertEquals(List.of("doctorId"), toList(specializes.path("adds")));
        // ModelResolver sorts fields by normalized name -- "code" < "id".
        assertEquals(List.of("code", "id"), toList(specializes.path("inherits")));
        assertTrue(specializes.path("removes").isEmpty());
    }

    /**
     * P6.4 (Box Inspector): the manifest is now a hierarchy -- an application box, one module box
     * per distinct {@code concept.module}, and per-concept field/rule boxes -- plus a top-level
     * capabilities list carrying its bound implementation (adapter) when one exists.
     */
    @Test
    void emitsApplicationModuleFieldRuleAndCapabilityBoxes() throws Exception {
        CompiledConcept invoice = new CompiledConcept(
                "Invoice", "Invoice", "invoices",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("total", "decimal", "java.math.BigDecimal", false, true, false)
                ),
                List.of(),
                List.of(new CompiledInvariant("InvoiceTotalPositive", "expression", "total", "total > 0")),
                null, null, "T1_DECLARED", "Billing"
        );
        Map<String, CompiledConcept> entities = new LinkedHashMap<>();
        entities.put(invoice.getName(), invoice);

        CompiledModel model = new CompiledModel(
                "acme", "1.0.0", "v1", entities,
                List.of(new CompiledCapability("SendReceipt", "notification", List.of())),
                List.of(new CompiledCapabilityBinding("SendReceipt", "notification-inproc")),
                List.of(), List.of()
        );

        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());
        new BoxManifestEmitter().emit(model, writer);

        JsonNode root = new ObjectMapper().readTree(
                tempDir.resolve(BoxManifestEmitter.RELATIVE_PATH).toFile());

        assertEquals("acme", root.path("application").path("name").asText());
        assertEquals("T2_GENERATED", root.path("application").path("truthLevel").asText());

        JsonNode modules = root.path("modules");
        assertEquals(1, modules.size());
        assertEquals("Billing", modules.get(0).path("name").asText());
        assertEquals(1, modules.get(0).path("conceptCount").asInt());

        JsonNode invoiceBox = findBox(root.path("boxes"), "Invoice");
        assertEquals("Billing", invoiceBox.path("module").asText());
        assertEquals("T1_DECLARED", invoiceBox.path("declaredTruthLevel").asText());
        assertEquals("concept", invoiceBox.path("graphKind").asText());
        assertEquals("Invoice", invoiceBox.path("graphName").asText());

        JsonNode fields = invoiceBox.path("fields");
        assertEquals(2, fields.size());
        JsonNode totalField = findByName(fields, "total");
        assertEquals("Invoice.total", totalField.path("graphName").asText());
        assertEquals("field", totalField.path("graphKind").asText());
        assertTrue(totalField.path("required").asBoolean());

        JsonNode rules = invoiceBox.path("rules");
        assertEquals(1, rules.size());
        assertEquals("InvoiceTotalPositive", rules.get(0).path("graphName").asText());
        assertEquals("invariant", rules.get(0).path("graphKind").asText());

        JsonNode capabilities = root.path("capabilities");
        assertEquals(1, capabilities.size());
        assertEquals("SendReceipt", capabilities.get(0).path("name").asText());
        assertEquals("notification-inproc", capabilities.get(0).path("implementation").path("adapter").asText());
    }

    private static JsonNode findByName(JsonNode arrayNode, String name) {
        for (JsonNode node : arrayNode) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("No entry found named " + name);
    }

    private static List<String> toList(JsonNode arrayNode) {
        List<String> out = new java.util.ArrayList<>();
        arrayNode.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static JsonNode findBox(JsonNode boxes, String conceptName) {
        for (JsonNode box : boxes) {
            if (conceptName.equals(box.path("conceptName").asText())) {
                return box;
            }
        }
        throw new AssertionError("No box found for concept " + conceptName);
    }
}
