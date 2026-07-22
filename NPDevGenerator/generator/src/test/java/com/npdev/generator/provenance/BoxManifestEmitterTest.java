package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private static JsonNode findBox(JsonNode boxes, String conceptName) {
        for (JsonNode box : boxes) {
            if (conceptName.equals(box.path("conceptName").asText())) {
                return box;
            }
        }
        throw new AssertionError("No box found for concept " + conceptName);
    }
}
