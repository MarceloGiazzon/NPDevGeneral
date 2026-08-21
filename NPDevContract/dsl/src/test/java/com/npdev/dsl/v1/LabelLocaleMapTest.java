package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledActionMetadata;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledDomainType;
import com.npdev.dsl.v1.compiled.CompiledEnumOption;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.6 (Roadmap Wave 1, 2026-08-19): real DSL-level tests for the per-locale label map -- the
 * seam CLAUDE.md's own four-place-chain warning is about: a {@link JsonModelParser} that reads
 * {@code $defs/localizableLabel}'s object form and a {@link CompiledModelCanonicalJson}/
 * {@link CompiledModelCanonicalJsonReader} pair that loses it would still compile and still show
 * every OTHER test green. This class exercises every label site the schema widened -- properties,
 * domainType.ui, concept.ui, field.ui, enumOptions, lifecycle states/transitions/nested
 * actionMetadata, panel actions -- through the real front door ({@link JsonModelParser} ->
 * {@link ModelCompiler} -> canonical write -> canonical read), not by hand-constructing AST/
 * Compiled records (which would prove nothing about the parser or the writer/reader).
 *
 * <p>Two non-negotiables from the task brief, proven directly: (1) a plain-string label keeps
 * working completely unchanged (a widening, not a replacement -- every existing model would break
 * at once otherwise); (2) the object form actually carries a locale map through parse, compile,
 * and a full canonical write/read/write round trip.
 */
class LabelLocaleMapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------------------------------------------------------------------
    // Plain-string labels: the non-negotiable "widening, not replacement" contract.
    // ---------------------------------------------------------------------------------------

    @Test
    void plainStringLabelsParseCompileAndCanonicalizeUnchangedAtEverySite() throws Exception {
        ModelAst ast = parse(coreModel(false));
        List<String> validationErrors = new com.npdev.dsl.v1.validation.SemanticValidator().validate(ast);
        assertTrue(validationErrors.isEmpty(), "expected no validation errors: " + validationErrors);

        // -- AST layer --
        assertEquals("Page size", ast.getProperties().get(0).label());
        assertTrue(ast.getProperties().get(0).labelLocales().isEmpty());

        assertEquals("Email address", ast.getDomainTypes().get(0).getUi().getLabel());
        assertTrue(ast.getDomainTypes().get(0).getUi().getLabelLocales().isEmpty());

        var widget = ast.getConcepts().get(0);
        assertEquals("Widget", widget.getUi().getLabel());
        assertTrue(widget.getUi().getLabelLocales().isEmpty());

        var nameField = fieldNamed(widget.getFields(), "name");
        assertEquals("Widget name", nameField.getUi().getLabel());
        assertTrue(nameField.getUi().getLabelLocales().isEmpty());

        var statusField = fieldNamed(widget.getFields(), "status");
        assertEquals("Open", statusField.getEnumOptions().get(0).getLabel());
        assertTrue(statusField.getEnumOptions().get(0).getLabelLocales().isEmpty());

        assertEquals("Opened", widget.getLifecycle().getStates().get(0).getLabel());
        assertTrue(widget.getLifecycle().getStates().get(0).getLabelLocales().isEmpty());
        assertEquals("Close", widget.getLifecycle().getTransitions().get(0).getActionLabel());
        assertTrue(widget.getLifecycle().getTransitions().get(0).getActionLabelLocales().isEmpty());
        assertEquals("Close the widget", widget.getLifecycle().getTransitions().get(0).getAction().getLabel());
        assertTrue(widget.getLifecycle().getTransitions().get(0).getAction().getLabelLocales().isEmpty());

        assertEquals("Approve", ast.getPanels().get(0).actions().get(0).label());
        assertTrue(ast.getPanels().get(0).actions().get(0).labelLocales().isEmpty());

        // -- Compiled layer --
        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertCompiledSitesPlain(compiled);

        // -- Canonical JSON: a plain-string label must stay a JSON string, byte-identical to the
        // pre-R5.6 shape, never wrapped in an object just because the writer now knows how to. --
        String json = CompiledModelCanonicalJson.toJson(compiled);
        JsonNode root = MAPPER.readTree(json);
        assertTrue(findProperty(root, "pageRows").path("label").isTextual(), "plain label must stay a JSON string");
        assertEquals("Page size", findProperty(root, "pageRows").path("label").asText());
        assertTrue(findDomainType(root, "Email").path("ui").path("label").isTextual());
        assertTrue(findConcept(root, "Widget").path("ui").path("label").isTextual());

        // -- Canonical round trip: write -> read -> write must be byte-identical. --
        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals(json, CompiledModelCanonicalJson.toJson(roundTripped),
                "a plain-string-labeled compiled model must survive a canonical write -> read -> write "
                        + "round trip unchanged");
        assertCompiledSitesPlain(roundTripped);
    }

    private static void assertCompiledSitesPlain(CompiledModel compiled) {
        CompiledProperty property = compiled.getProperties().get(0);
        assertEquals("Page size", property.label());
        assertTrue(property.labelLocales().isEmpty());

        CompiledDomainType domainType = compiled.getDomainTypes().get(0);
        assertEquals("Email address", domainType.getUi().getLabel());
        assertTrue(domainType.getUi().getLabelLocales().isEmpty());

        CompiledConcept widget = compiled.findConcept("Widget").orElseThrow();
        assertEquals("Widget", widget.getUi().getLabel());
        assertTrue(widget.getUi().getLabelLocales().isEmpty());

        CompiledField nameField = compiledFieldNamed(widget.getFields(), "name");
        assertEquals("Widget name", nameField.getUi().getLabel());
        assertTrue(nameField.getUi().getLabelLocales().isEmpty());

        CompiledField statusField = compiledFieldNamed(widget.getFields(), "status");
        CompiledEnumOption openOption = statusField.getEnumOptions().get(0);
        assertEquals("Open", openOption.getLabel());
        assertTrue(openOption.getLabelLocales().isEmpty());

        CompiledStateMachineState openedState = widget.getLifecycle().getStates().get(0);
        assertEquals("Opened", openedState.getLabel());
        assertTrue(openedState.getLabelLocales().isEmpty());

        CompiledStateTransition closeTransition = widget.getLifecycle().getTransitions().get(0);
        assertEquals("Close", closeTransition.getActionLabel());
        assertTrue(closeTransition.getActionLabelLocales().isEmpty());
        CompiledActionMetadata action = closeTransition.getAction();
        assertEquals("Close the widget", action.getLabel());
        assertTrue(action.getLabelLocales().isEmpty());

        CompiledPanel panel = compiled.getPanels().stream()
                .filter(p -> "WidgetPanel".equals(p.name())).findFirst().orElseThrow();
        CompiledPanelAction approve = panel.actions().get(0);
        assertEquals("Approve", approve.label());
        assertTrue(approve.labelLocales().isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // Object-form labels: the actual R5.6 feature -- default text + per-locale overrides,
    // threaded through parse, compile, and a full canonical round trip at every site.
    // ---------------------------------------------------------------------------------------

    @Test
    void objectFormLabelsCarryDefaultAndLocalesThroughParseCompileAndCanonicalRoundTrip() throws Exception {
        ModelAst ast = parse(coreModel(true));
        List<String> validationErrors = new com.npdev.dsl.v1.validation.SemanticValidator().validate(ast);
        assertTrue(validationErrors.isEmpty(), "expected no validation errors: " + validationErrors);

        // -- AST layer: label() resolves to "default", labelLocales() carries the overrides. --
        assertEquals("Page size", ast.getProperties().get(0).label());
        assertEquals(Map.of("pt-BR", "Tamanho da pagina", "en", "Page size"),
                ast.getProperties().get(0).labelLocales());

        assertEquals("Email address", ast.getDomainTypes().get(0).getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Endereco de email"), ast.getDomainTypes().get(0).getUi().getLabelLocales());

        var widget = ast.getConcepts().get(0);
        assertEquals("Widget", widget.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Dispositivo"), widget.getUi().getLabelLocales());

        var nameField = fieldNamed(widget.getFields(), "name");
        assertEquals("Widget name", nameField.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Nome do dispositivo"), nameField.getUi().getLabelLocales());

        var statusField = fieldNamed(widget.getFields(), "status");
        assertEquals("Open", statusField.getEnumOptions().get(0).getLabel());
        assertEquals(Map.of("pt-BR", "Aberto"), statusField.getEnumOptions().get(0).getLabelLocales());

        assertEquals("Opened", widget.getLifecycle().getStates().get(0).getLabel());
        assertEquals(Map.of("pt-BR", "Aberto"), widget.getLifecycle().getStates().get(0).getLabelLocales());
        assertEquals("Close", widget.getLifecycle().getTransitions().get(0).getActionLabel());
        assertEquals(Map.of("pt-BR", "Fechar"), widget.getLifecycle().getTransitions().get(0).getActionLabelLocales());
        assertEquals("Close the widget", widget.getLifecycle().getTransitions().get(0).getAction().getLabel());
        assertEquals(Map.of("pt-BR", "Fechar o dispositivo"),
                widget.getLifecycle().getTransitions().get(0).getAction().getLabelLocales());

        assertEquals("Approve", ast.getPanels().get(0).actions().get(0).label());
        assertEquals(Map.of("pt-BR", "Aprovar"), ast.getPanels().get(0).actions().get(0).labelLocales());

        // -- Compiled layer --
        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertCompiledSitesLocalized(compiled);

        // -- Canonical JSON: an object-form label must stay an object, with "default" plus every
        // declared locale key (sorted -- CompiledModelCanonicalJson#toLabelNode uses a TreeMap so
        // resolution order is deterministic, matching LabelResolver's own javadoc claim). --
        String json = CompiledModelCanonicalJson.toJson(compiled);
        JsonNode root = MAPPER.readTree(json);
        JsonNode propertyLabel = findProperty(root, "pageRows").path("label");
        assertTrue(propertyLabel.isObject(), "object-form label must stay an object in canonical JSON");
        assertEquals("Page size", propertyLabel.path("default").asText());
        assertEquals("Page size", propertyLabel.path("en").asText());
        assertEquals("Tamanho da pagina", propertyLabel.path("pt-BR").asText());
        assertEquals(List.of("default", "en", "pt-BR"),
                java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(propertyLabel.fieldNames(), 0), false)
                        .sorted().toList(),
                "canonical JSON must carry exactly default + every declared locale key, nothing more");

        // -- Canonical round trip: write -> read -> write must be byte-identical, and every
        // labelLocales map must come back exactly as declared -- this is the REG-104-shaped seam:
        // a reader that silently drops labelLocales would still leave `json` looking fine but this
        // assertion would fail on the SECOND write. --
        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals(json, CompiledModelCanonicalJson.toJson(roundTripped),
                "an object-form-labeled compiled model must survive a canonical write -> read -> write "
                        + "round trip unchanged");
        assertCompiledSitesLocalized(roundTripped);
    }

    private static void assertCompiledSitesLocalized(CompiledModel compiled) {
        CompiledProperty property = compiled.getProperties().get(0);
        assertEquals("Page size", property.label());
        assertEquals(Map.of("pt-BR", "Tamanho da pagina", "en", "Page size"), property.labelLocales());

        CompiledDomainType domainType = compiled.getDomainTypes().get(0);
        assertEquals("Email address", domainType.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Endereco de email"), domainType.getUi().getLabelLocales());

        CompiledConcept widget = compiled.findConcept("Widget").orElseThrow();
        assertEquals("Widget", widget.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Dispositivo"), widget.getUi().getLabelLocales());

        CompiledField nameField = compiledFieldNamed(widget.getFields(), "name");
        assertEquals("Widget name", nameField.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Nome do dispositivo"), nameField.getUi().getLabelLocales());

        CompiledField statusField = compiledFieldNamed(widget.getFields(), "status");
        CompiledEnumOption openOption = statusField.getEnumOptions().get(0);
        assertEquals("Open", openOption.getLabel());
        assertEquals(Map.of("pt-BR", "Aberto"), openOption.getLabelLocales());

        CompiledStateMachineState openedState = widget.getLifecycle().getStates().get(0);
        assertEquals("Opened", openedState.getLabel());
        assertEquals(Map.of("pt-BR", "Aberto"), openedState.getLabelLocales());

        CompiledStateTransition closeTransition = widget.getLifecycle().getTransitions().get(0);
        assertEquals("Close", closeTransition.getActionLabel());
        assertEquals(Map.of("pt-BR", "Fechar"), closeTransition.getActionLabelLocales());
        CompiledActionMetadata action = closeTransition.getAction();
        assertEquals("Close the widget", action.getLabel());
        assertEquals(Map.of("pt-BR", "Fechar o dispositivo"), action.getLabelLocales());

        CompiledPanel panel = compiled.getPanels().stream()
                .filter(p -> "WidgetPanel".equals(p.name())).findFirst().orElseThrow();
        CompiledPanelAction approve = panel.actions().get(0);
        assertEquals("Approve", approve.label());
        assertEquals(Map.of("pt-BR", "Aprovar"), approve.labelLocales());
    }

    // ---------------------------------------------------------------------------------------
    // Schema-level negative: the object form's "default" is required, not optional.
    // ---------------------------------------------------------------------------------------

    @Test
    void objectFormLabelWithoutDefaultFailsSchemaValidation() {
        String json = """
                {
                  "dslVersion": "1.0.0", "namespace": "wms.label.neg", "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "ui": { "label": { "pt-BR": "Dispositivo" } },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """;
        IOException thrown = assertThrows(IOException.class, () -> parse(json));
        assertTrue(thrown.getMessage() != null
                        && (thrown.getMessage().contains("default") || thrown.getMessage().contains("required")),
                "an object-form label missing the required 'default' key must be refused at schema "
                        + "validation, got: " + thrown.getMessage());
    }

    @Test
    void emptyStringLabelSiteStillFailsSchemaValidationExactlyAsBeforeR56() {
        // The pre-existing minLength:1 rule on the plain-string arm must survive the widening
        // untouched -- this predates R5.6 and must not have regressed.
        String json = """
                {
                  "dslVersion": "1.0.0", "namespace": "wms.label.neg2", "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "ui": { "label": "" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """;
        // An empty string label fails the oneOf (too short for the string arm, not an object for
        // the object arm) -- schema validation must still reject it, not silently accept it as "no
        // label declared".
        assertThrows(IOException.class, () -> parse(json));
    }

    // ---------------------------------------------------------------------------------------
    // Real corpus proof: dsl-conformance-max/Input/model.json's own R5.6 coverage fixture
    // (WidgetOrder.ui.label) round-trips through the exact same pipeline.
    // ---------------------------------------------------------------------------------------

    @Test
    void corpusModelsLocaleLabelSiteRoundTripsThroughCanonicalJson() throws Exception {
        Path modelPath = resolvePath(List.of(
                Path.of("..", "..", "NPDevSamples", "dsl-conformance-max", "Input", "model.json"),
                Path.of("..", "..", "..", "NPDevSamples", "dsl-conformance-max", "Input", "model.json")
        ));

        ModelAst ast = new JsonModelParser().parse(modelPath);
        var widgetOrder = ast.getConcepts().stream()
                .filter(c -> "WidgetOrder".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("Widget order", widgetOrder.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Pedido de widget"), widgetOrder.getUi().getLabelLocales());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals(json, CompiledModelCanonicalJson.toJson(roundTripped),
                "the corpus's own R5.6 fixture must survive a canonical write -> read -> write round trip");

        CompiledConcept compiledWidgetOrder = roundTripped.findConcept("WidgetOrder").orElseThrow();
        assertEquals("Widget order", compiledWidgetOrder.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Pedido de widget"), compiledWidgetOrder.getUi().getLabelLocales());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static ModelAst parse(String json) throws IOException {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static com.npdev.dsl.v1.ast.FieldAst fieldNamed(List<com.npdev.dsl.v1.ast.FieldAst> fields, String name) {
        return fields.stream().filter(f -> name.equals(f.getName())).findFirst().orElseThrow();
    }

    private static CompiledField compiledFieldNamed(List<CompiledField> fields, String name) {
        return fields.stream().filter(f -> name.equals(f.getName())).findFirst().orElseThrow();
    }

    private static JsonNode findProperty(JsonNode root, String name) {
        for (JsonNode p : root.path("properties")) {
            if (name.equals(p.path("name").asText())) {
                return p;
            }
        }
        throw new IllegalStateException("property not found: " + name);
    }

    private static JsonNode findDomainType(JsonNode root, String name) {
        for (JsonNode dt : root.path("domainTypes")) {
            if (name.equals(dt.path("name").asText())) {
                return dt;
            }
        }
        throw new IllegalStateException("domainType not found: " + name);
    }

    private static JsonNode findConcept(JsonNode root, String name) {
        for (JsonNode c : root.path("concepts")) {
            if (name.equals(c.path("name").asText())) {
                return c;
            }
        }
        throw new IllegalStateException("concept not found: " + name);
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("Unable to resolve dsl-conformance-max model path.");
    }

    /**
     * A single model exercising all 9 label sites this test file checks: {@code properties[].label},
     * {@code domainTypes[].ui.label}, {@code concepts[].ui.label}, {@code concepts[].fields[].ui.label},
     * {@code concepts[].fields[].enumValues[].label}, {@code concepts[].lifecycle.states[].label},
     * {@code concepts[].lifecycle.transitions[].actionLabel},
     * {@code concepts[].lifecycle.transitions[].action.label} (nested actionMetadata), and
     * {@code panels[].actions[].label}. {@code localized} switches every site between the
     * pre-existing plain-string form and R5.6's object form.
     */
    private static String coreModel(boolean localized) {
        String propertyLabel = localized
                ? "{ \"default\": \"Page size\", \"pt-BR\": \"Tamanho da pagina\", \"en\": \"Page size\" }"
                : "\"Page size\"";
        String domainTypeLabel = localized
                ? "{ \"default\": \"Email address\", \"pt-BR\": \"Endereco de email\" }"
                : "\"Email address\"";
        String conceptUiLabel = localized
                ? "{ \"default\": \"Widget\", \"pt-BR\": \"Dispositivo\" }"
                : "\"Widget\"";
        String fieldUiLabel = localized
                ? "{ \"default\": \"Widget name\", \"pt-BR\": \"Nome do dispositivo\" }"
                : "\"Widget name\"";
        String enumOptionLabel = localized
                ? "{ \"default\": \"Open\", \"pt-BR\": \"Aberto\" }"
                : "\"Open\"";
        String stateLabel = localized
                ? "{ \"default\": \"Opened\", \"pt-BR\": \"Aberto\" }"
                : "\"Opened\"";
        String transitionActionLabel = localized
                ? "{ \"default\": \"Close\", \"pt-BR\": \"Fechar\" }"
                : "\"Close\"";
        String nestedActionLabel = localized
                ? "{ \"default\": \"Close the widget\", \"pt-BR\": \"Fechar o dispositivo\" }"
                : "\"Close the widget\"";
        String panelActionLabel = localized
                ? "{ \"default\": \"Approve\", \"pt-BR\": \"Aprovar\" }"
                : "\"Approve\"";

        return """
                {
                  "dslVersion": "1.0.0", "namespace": "wms.labelmap", "version": "1.0",
                  "propertyScopes": [ { "name": "tenant" } ],
                  "properties": [
                    { "name": "pageRows", "type": "int", "default": 25, "settableAt": ["tenant"],
                      "label": %s }
                  ],
                  "domainTypes": [
                    { "name": "Email", "baseType": "string", "ui": { "label": %s } }
                  ],
                  "concepts": [
                    {
                      "name": "Widget",
                      "ui": { "label": %s },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "ui": { "label": %s } },
                        { "name": "status", "type": "enum", "required": true,
                          "enumValues": [ { "value": "Open", "label": %s }, "Closed" ] }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "states": [
                          { "value": "Open", "label": %s, "initial": true },
                          { "value": "Closed", "terminal": true }
                        ],
                        "transitions": [
                          { "from": "Open", "to": "Closed", "actionLabel": %s,
                            "action": { "label": %s } }
                        ]
                      }
                    }
                  ],
                  "panels": [
                    { "name": "WidgetPanel", "route": "/widgets", "actions": [
                      { "name": "approve", "binding": "conceptMutation", "concept": "Widget",
                        "operation": "update", "label": %s } ] }
                  ]
                }
                """.formatted(propertyLabel, domainTypeLabel, conceptUiLabel, fieldUiLabel, enumOptionLabel,
                stateLabel, transitionActionLabel, nestedActionLabel, panelActionLabel);
    }
}
