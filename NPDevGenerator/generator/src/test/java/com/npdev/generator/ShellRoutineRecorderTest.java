package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3.9: pins the routine recorder shipped in {@code shell.js.mustache} against the property that
 * makes it real rather than decorative -- a recording, composed exactly the way
 * {@code npdev_explore.compose_engine_request} composes any checked-in routine file (drop
 * {@code targetPath}, inject an absolute {@code targetUrl}), validates against the PINNED engine
 * schema ({@code schemas/ai/scrapforai-routine.schema.json}, never hand-edited -- see
 * {@code check-routine-corpus-conformance.py}).
 *
 * <p><b>What this does NOT prove.</b> There is no JS engine dependency wired into this Gradle module
 * (and none was added -- the recorder is plain vanilla JS, no new dependency of any kind), so this
 * test does not execute {@code shell.js.mustache}'s actual recorder code. It instead (1) hand-builds
 * the JSON shape that recorder's {@code recorderComposeDocument()}/{@code recorderBuildSelector()}
 * functions are documented to produce for a representative click-path (goto, fill-from-credential,
 * click, selectOption, check, assertTextContains, screenshot) and validates THAT shape against the
 * pinned schema, and (2) greps the actual template source for the specific properties that make the
 * feature opt-in and safe (role gate, explicit query-flag gate, the step cap matching the schema's own
 * {@code maxItems}, and that password fields are never recorded as literal values). A true end-to-end
 * record-in-a-real-browser-then-replay-through-npdev-explore proof was NOT performed by this test.
 */
@DisplayName("shell.js.mustache routine recorder -- output conforms to the pinned engine schema")
class ShellRoutineRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "http://127.0.0.1:8080";

    @Test
    @DisplayName("a representative recorded session, composed like npdev_explore does, validates against the pinned schema")
    void recordedSessionComposesToAValidRequest() throws Exception {
        ObjectNode routine = MAPPER.createObjectNode();
        routine.put("scenarioName", "recorded-routine");
        routine.put("targetPath", "/npdev-business-ui/");
        ObjectNode options = routine.putObject("options");
        options.put("headless", true);
        options.put("screenshots", "onFailure");
        options.put("collectDomOnFailure", true);

        ArrayNode steps = routine.putArray("steps");
        steps.addObject().put("action", "goto").put("url", "$targetUrl").put("label", "Open the app");
        ObjectNode fillApiKey = steps.addObject();
        fillApiKey.put("action", "fill");
        fillApiKey.put("selector", "#apiKey");
        fillApiKey.put("valueFromCredential", "apiKey");
        fillApiKey.put("label", "Fill (from credential -- not recorded literally)");
        steps.addObject().put("action", "click")
                .put("selector", "#sideNav a[href=\"#concept-Note\"]")
                .put("label", "Click \"Note\"");
        ObjectNode fillTitle = steps.addObject();
        fillTitle.put("action", "fill");
        fillTitle.put("selector", "#concept-Note [name=\"title\"]");
        fillTitle.put("value", "Recorded via the in-app recorder");
        fillTitle.put("label", "Fill");
        steps.addObject().put("action", "selectOption")
                .put("selector", "#concept-Note [name=\"status\"]")
                .put("value", "open")
                .put("label", "Select");
        steps.addObject().put("action", "check")
                .put("selector", "#concept-Note [name=\"urgent\"]")
                .put("label", "Check");
        steps.addObject().put("action", "click")
                .put("selector", "#concept-Note button:has-text(\"Save\")")
                .put("label", "Submit \"Save\" (Enter key)");
        steps.addObject().put("action", "assertTextContains")
                .put("selector", "#concept-Note tbody tr")
                .put("text", "Recorded via the in-app recorder")
                .put("label", "Assert text");
        steps.addObject().put("action", "screenshot").put("name", "shot_9").put("label", "Screenshot");

        JsonNode request = composeEngineRequest(routine, BASE_URL);
        Set<ValidationMessage> violations = pinnedSchema().validate(request);
        assertTrue(violations.isEmpty(), "recorder-shaped routine rejected by the pinned schema: " + violations);
    }

    @Test
    @DisplayName("self-test: an action the engine schema does not define is rejected -- proves the assertion above is load-bearing")
    void unknownActionIsRejected() throws Exception {
        ObjectNode routine = MAPPER.createObjectNode();
        routine.put("targetPath", "/npdev-business-ui/");
        ArrayNode steps = routine.putArray("steps");
        steps.addObject().put("action", "teleport").put("selector", "#nowhere");

        JsonNode request = composeEngineRequest(routine, BASE_URL);
        Set<ValidationMessage> violations = pinnedSchema().validate(request);
        assertFalse(violations.isEmpty(), "an unknown action was accepted -- this test proves nothing");
    }

    @Test
    @DisplayName("the recorder's step cap in the template matches the pinned schema's own steps maxItems")
    void recorderStepCapMatchesSchemaMaxItems() throws Exception {
        JsonNode schema = MAPPER.readTree(pinnedSchemaFile().toFile());
        int schemaMaxItems = schema.at("/$defs/__schema7/maxItems").asInt(-1);
        assertTrue(schemaMaxItems > 0, "could not resolve steps maxItems from the pinned schema -- schema shape changed");

        String source = Files.readString(shellJsTemplatePath());
        assertTrue(source.contains("var RECORDER_MAX_STEPS = " + schemaMaxItems + ";"),
                "shell.js.mustache's RECORDER_MAX_STEPS is out of sync with the pinned schema's steps "
                        + "maxItems (" + schemaMaxItems + ") -- a recording could silently exceed what the "
                        + "engine will ever accept");
    }

    @Test
    @DisplayName("the recorder is gated behind superuser identity AND an explicit opt-in, never on for an ordinary visitor")
    void recorderIsGatedNotDefaultOn() throws Exception {
        String source = Files.readString(shellJsTemplatePath());
        assertTrue(source.contains("if (state.isSuperUser && recorderOptedIn())"),
                "the recorder's init call must be gated on BOTH isSuperUser and explicit opt-in");
        assertTrue(source.contains("npdevRecord"),
                "the opt-in query flag must be explicit and named, not inferred");
        assertTrue(source.contains("RECORDER_ENABLED_KEY"),
                "opt-in must persist via a dedicated storage key, not piggyback on an unrelated flag");
    }

    @Test
    @DisplayName("a password-typed field is recorded via valueFromCredential, never as a literal value")
    void passwordFieldsAreNeverRecordedLiterally() throws Exception {
        String source = Files.readString(shellJsTemplatePath());
        assertTrue(source.contains("valueFromCredential: el.name || \"password\""),
                "a recorded password field must never write the typed secret into the routine file");
    }

    // ------------------------------------------------------------------------------------------
    // Mirrors NPDevCli/npdev_explore.py's compose_engine_request: drop the file-only `targetPath`,
    // inject the absolute `targetUrl` the engine actually requires, forward everything else as-is.
    // ------------------------------------------------------------------------------------------
    private static JsonNode composeEngineRequest(ObjectNode routine, String baseUrl) {
        ObjectNode request = MAPPER.createObjectNode();
        String targetPath = routine.has("targetPath") ? routine.get("targetPath").asText() : "/npdev-business-ui/";
        request.put("targetUrl", baseUrl.replaceAll("/+$", "") + targetPath);
        if (routine.has("scenarioName")) request.set("scenarioName", routine.get("scenarioName"));
        if (routine.has("options")) request.set("options", routine.get("options"));
        if (routine.has("variables")) request.set("variables", routine.get("variables"));
        if (routine.has("credentials")) request.set("credentials", routine.get("credentials"));
        if (routine.has("caller")) request.set("caller", routine.get("caller"));
        request.set("steps", routine.get("steps"));
        return request;
    }

    private static JsonSchema pinnedSchema() throws Exception {
        try (InputStream stream = Files.newInputStream(pinnedSchemaFile())) {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(stream);
        }
    }

    private static Path pinnedSchemaFile() {
        return repoRoot().resolve("schemas/ai/scrapforai-routine.schema.json");
    }

    private static Path shellJsTemplatePath() {
        return repoRoot().resolve(
                "NPDevGenerator/generator/src/main/resources/npdev-templates/shell.js.mustache");
    }

    /** The repo root, identified by CONTENTS and never by directory name (REG-144). */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("NPDevContract"))
                    && Files.isDirectory(candidate.resolve("NPDevGenerator"))
                    && Files.isDirectory(candidate.resolve("NPDevKernel"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not identify the repo root by contents from " + Path.of("").toAbsolutePath());
    }
}
