package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENG-3: the checks /model-reload runs before it swaps the live model. */
class ModelReloadPreflightTest {

    @TempDir
    Path tempDir;

    private static String concept(String name) {
        return "{\"name\":\"" + name + "\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true}]}";
    }

    private static String model(String... conceptNames) {
        StringBuilder concepts = new StringBuilder();
        for (String name : conceptNames) {
            if (concepts.length() > 0) {
                concepts.append(',');
            }
            concepts.append(concept(name));
        }
        return "{\"dslVersion\":\"1.0.0\",\"namespace\":\"reload.test\",\"version\":\"1.0\","
                + "\"concepts\":[" + concepts + "]}";
    }

    private static CompiledModel compiled(String json) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(json)));
    }

    private Path write(String json) throws Exception {
        return Files.writeString(tempDir.resolve("model-" + System.nanoTime() + ".json"), json);
    }

    @Test
    void aValidModelWithTheSameConceptsIsAccepted() throws Exception {
        CompiledModel live = compiled(model("Alpha", "Beta"));

        ModelReloadPreflight.Outcome outcome = ModelReloadPreflight.prepare(write(model("Alpha", "Beta")), live, true);

        assertTrue(outcome.ok(), String.valueOf(outcome.message()) + outcome.details());
        assertNotNull(outcome.model());
        assertNull(outcome.code());
    }

    @Test
    void aSemanticallyInvalidModelIsRefused() throws Exception {
        CompiledModel live = compiled(model("Alpha"));
        String invalid = "{\"dslVersion\":\"1.0.0\",\"namespace\":\"reload.test\",\"version\":\"1.0\","
                + "\"concepts\":[" + concept("Alpha") + "],"
                + "\"flows\":[{\"name\":\"Broken\",\"concept\":\"Nope\",\"steps\":["
                + "{\"name\":\"s\",\"type\":\"return\",\"value\":\"input\"}]}]}";

        ModelReloadPreflight.Outcome outcome = ModelReloadPreflight.prepare(write(invalid), live, true);

        assertFalse(outcome.ok());
        assertEquals("MODEL_INVALID", outcome.code());
        assertFalse(outcome.details().isEmpty());
    }

    @Test
    void aReloadThatDropsALiveConceptIsRefusedAndNamesIt() throws Exception {
        CompiledModel live = compiled(model("Alpha", "Beta"));

        ModelReloadPreflight.Outcome outcome = ModelReloadPreflight.prepare(write(model("Alpha")), live, true);

        assertFalse(outcome.ok());
        assertEquals("RELOAD_REMOVES_CONCEPTS", outcome.code());
        assertTrue(outcome.details().stream().anyMatch(detail -> detail.contains("Beta")), outcome.details().toString());
    }

    @Test
    void aNewConceptIsRefusedWhenItsTableCannotBeCreatedLiveAndAcceptedOtherwise() throws Exception {
        CompiledModel live = compiled(model("Alpha"));
        Path candidate = write(model("Alpha", "Gamma"));

        ModelReloadPreflight.Outcome refused = ModelReloadPreflight.prepare(candidate, live, false);
        assertFalse(refused.ok());
        assertEquals("NEW_CONCEPT_PROVISIONING_DISABLED", refused.code());
        assertTrue(refused.details().contains("Gamma"));

        assertTrue(ModelReloadPreflight.prepare(candidate, live, true).ok());
    }

    // ---- LIVE-1: field-level diff ---------------------------------------------------------------

    private static String modelWithFields(String... extraFieldJson) {
        return "{\"dslVersion\":\"1.0.0\",\"namespace\":\"reload.test\",\"version\":\"1.0\",\"concepts\":["
                + "{\"name\":\"Alpha\",\"fields\":[{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true}"
                + (extraFieldJson.length == 0 ? "" : "," + String.join(",", extraFieldJson)) + "]}]}";
    }

    @Test
    void aNewOptionalScalarFieldOnAnExistingConceptIsAcceptedAndReportedForAnAlter() throws Exception {
        CompiledModel live = compiled(modelWithFields());

        ModelReloadPreflight.Outcome outcome = ModelReloadPreflight.prepare(
                write(modelWithFields("{\"name\":\"nickname\",\"type\":\"string\"}")), live, true);

        assertTrue(outcome.ok(), String.valueOf(outcome.message()) + outcome.details());
        assertEquals(1, outcome.addedFields().size());
        assertEquals("Alpha.nickname", outcome.addedFields().get(0).label());
    }

    @Test
    void aNewFieldIsRefusedWhenItsColumnCannotBeAddedLive() throws Exception {
        CompiledModel live = compiled(modelWithFields());

        ModelReloadPreflight.Outcome outcome = ModelReloadPreflight.prepare(
                write(modelWithFields("{\"name\":\"nickname\",\"type\":\"string\"}")), live, false);

        assertFalse(outcome.ok());
        assertEquals("NEW_FIELD_PROVISIONING_DISABLED", outcome.code());
        assertTrue(outcome.details().contains("Alpha.nickname"), outcome.details().toString());
    }

    @Test
    void aRemovedRetypedOrRequiredFieldIsRefusedWithEveryOffenderNamed() throws Exception {
        CompiledModel live = compiled(modelWithFields("{\"name\":\"qty\",\"type\":\"int\"}"));

        ModelReloadPreflight.Outcome removed = ModelReloadPreflight.prepare(write(modelWithFields()), live, true);
        assertFalse(removed.ok());
        assertEquals("RELOAD_UNSUPPORTED_FIELD_CHANGES", removed.code());
        assertTrue(removed.details().get(0).contains("Alpha.qty"), removed.details().toString());

        ModelReloadPreflight.Outcome retyped = ModelReloadPreflight.prepare(
                write(modelWithFields("{\"name\":\"qty\",\"type\":\"string\"}")), live, true);
        assertEquals("RELOAD_UNSUPPORTED_FIELD_CHANGES", retyped.code());

        ModelReloadPreflight.Outcome required = ModelReloadPreflight.prepare(
                write(modelWithFields("{\"name\":\"qty\",\"type\":\"int\"}",
                        "{\"name\":\"must\",\"type\":\"string\",\"required\":true}")), live, true);
        assertEquals("RELOAD_UNSUPPORTED_FIELD_CHANGES", required.code());
        assertTrue(required.details().get(0).contains("Alpha.must"), required.details().toString());
    }
}
