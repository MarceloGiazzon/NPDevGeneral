package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE-1: the generated pieces a field added by a live {@code /model-reload} relies on. The behaviour
 * itself is proven by the runtime-support and RuntimeHost tests and the live probe; this pins the
 * generated contract so a template edit that drops one half fails here rather than at runtime.
 */
final class EntityEmitterLiveFieldExtrasTest {

    @TempDir
    Path tempDir;

    @Test
    void everyGeneratedEntityCarriesTheTransientExtrasBagWithoutDependingOnRuntimeSupport() throws Exception {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        new EntityEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(tempDir, new RegenerationPolicy())).emit(model);

        String entity = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/entities/Order.java"));
        assertTrue(entity.contains("@Transient"), entity);
        assertTrue(entity.contains("@com.fasterxml.jackson.annotation.JsonAnySetter"), entity);
        assertTrue(entity.contains("public void putExtraField(String name, Object value)"), entity);
        assertTrue(entity.contains("@com.fasterxml.jackson.annotation.JsonAnyGetter"), entity);
        assertTrue(entity.contains("public java.util.Map<String, Object> extraFields()"), entity);
        // Sealed pack jars compile this same template with no runtime-support on the classpath.
        assertFalse(entity.contains("com.npdev.runtime.support"), entity);
    }

    @Test
    void theGeneratedServiceAndControllerReadTheLiveModelForNewFields() throws Exception {
        String service = template("service-base.mustache");
        assertTrue(service.contains("runtimeSupport.liveFieldNames(CONCEPT_NAME)"));
        assertTrue(service.contains("!ALL_FIELD_NAMES.contains(fieldName) && !liveFieldNames.contains(fieldName)"));

        String controller = template("business-concept-crud-controller.mustache");
        assertTrue(controller.contains("return withLiveFields(baked);"));
        assertTrue(controller.contains("LiveFieldMetadata.addedFields("));
        assertTrue(controller.contains("ExtraFieldAccess.all(source)"));
    }

    /** REG-253: the required pre-check must let a defaulted field through to the create payload. */
    @Test
    void theGeneratedServiceDoesNotRefuseARequiredFieldThatHasADeclaredDefault() throws Exception {
        String service = template("service-base.mustache");
        assertTrue(service.contains("!(value == null && runtimeSupport.hasCreateDefault(CONCEPT_NAME, requiredField))"));
    }

    private static String template(String name) throws Exception {
        try (InputStream in = EntityEmitterLiveFieldExtrasTest.class.getClassLoader()
                .getResourceAsStream("npdev-templates/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
