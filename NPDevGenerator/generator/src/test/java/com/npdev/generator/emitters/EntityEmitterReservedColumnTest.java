package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-64/F10 (docs/FINAL_OPEN_ITEMS_PLAN.md): the same reserved-column collision
 * SchemaRealizationEmitterReservedColumnTest already pins for SQL-DDL emission, now pinned at the
 * Java-entity layer too -- the layer that actually broke first for Claude Support Desk. Before this
 * fix, EntityEmitter blindly wrote BOTH the platform's own implicit tenant_id/version field and the
 * model's colliding one into the same generated class, producing a duplicate-field javac error
 * instead of this actionable message.
 */
final class EntityEmitterReservedColumnTest {

    @TempDir
    Path tempDir;

    private EntityEmitter emitter() {
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());
        return new EntityEmitter(templates, writer);
    }

    @Test
    void fieldNamedTenantIdCollidesWithThePlatformColumnAndFailsFast() {
        CompiledConcept order = new CompiledConcept(
                "DiningOrder", "DiningOrder", "dining_orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("tenantId", "uuid", "java.util.UUID", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> emitter().emit(model));
        assertTrue(exception.getMessage().contains("DiningOrder"), exception.getMessage());
        assertTrue(exception.getMessage().contains("tenant_id"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Rename this field"), exception.getMessage());
    }

    @Test
    void fieldNamedVersionAlsoCollidesAndFailsFast() {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("version", "int", "Integer", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        assertThrows(IllegalStateException.class, () -> emitter().emit(model));
    }

    @Test
    void ordinaryFieldNamesAreUnaffectedAndTheEntityIsWritten() throws Exception {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("tenantName", "string", "String", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        emitter().emit(model);

        String entity = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/entities/Order.java"));
        assertTrue(entity.contains("tenantName"), entity);
    }
}
