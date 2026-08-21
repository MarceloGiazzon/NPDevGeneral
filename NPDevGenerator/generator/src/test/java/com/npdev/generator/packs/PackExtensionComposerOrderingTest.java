package com.npdev.generator.packs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.emitters.BusinessUiEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-10 step 4 (UI composition with app-controlled ordering): live proof for {@link
 * PackExtensionComposer#composeExtensionsWithOrdering}. Reuses the exact same {@code
 * clinicbase}/{@code clinicext} synthetic pair {@link PackExtensionComposerTest} established for
 * steps 1-3, for the same reason documented there -- every real pack in this repo is sealed, so no
 * real pack can be successfully extended.
 *
 * <p>Three things are proven here that steps 1-3 did not need to: (1) the DEFAULT order (no app
 * directive) is base-fields-then-extension-fields-appended, unchanged from what {@link
 * PackExtensionComposer#composeExtensions} already produced; (2) an app's own {@code
 * metadata.fieldOrder} directive overrides that default, deterministically, leaving an unmentioned
 * field appended rather than dropped; (3) extension-added fields are attributed to the pack that
 * added them, and that attribution is what {@link BusinessUiEmitter} actually writes into the
 * generated UI manifest a browser reads.
 */
class PackExtensionComposerOrderingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLINICBASE_PACK_JSON = """
            {
              "dslVersion": "1.0.0",
              "pack": "clinicbase",
              "version": "1.0.0",
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "name", "type": "string", "required": true, "maxLength": 120 }
                ]}
              ]
            }
            """;

    private static final String CLINICEXT_PACK_JSON = """
            {
              "dslVersion": "1.0.0",
              "pack": "clinicext",
              "version": "1.0.0",
              "metadata": { "extends": { "pack": "clinicbase", "concept": "Patient" } },
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "specialty", "type": "string", "required": false, "maxLength": 80 }
                ]}
              ]
            }
            """;

    private static final String CLINICEXT2_PACK_JSON = """
            {
              "dslVersion": "1.0.0",
              "pack": "clinicext2",
              "version": "1.0.0",
              "metadata": { "extends": { "pack": "clinicbase", "concept": "Patient" } },
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "licenseNumber", "type": "string", "required": false, "maxLength": 40 }
                ]}
              ]
            }
            """;

    // ---- default order (no app directive) -------------------------------------------------------

    @Test
    void defaultOrder_isBaseFieldsThenEachExtensionsAddedFieldsInProcessingOrder(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, null);

        CompiledConcept patient = result.model().findConcept("clinicbase::Patient").orElseThrow();
        assertEquals(List.of("id", "name", "specialty", "licenseNumber"), fieldNames(patient),
                "expected base fields, then clinicext's added field, then clinicext2's added field, unchanged");
    }

    @Test
    void appModelWithNoMetadataFieldOrderKeyReproducesTheDefaultOrder(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);
        JsonNode appModelWithUnrelatedMetadata = MAPPER.readTree("""
                { "metadata": { "someOtherKey": "value" } }
                """);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelWithUnrelatedMetadata);

        CompiledConcept patient = result.model().findConcept("clinicbase::Patient").orElseThrow();
        assertEquals(List.of("id", "name", "specialty", "licenseNumber"), fieldNames(patient));
    }

    // ---- app-controlled ordering -------------------------------------------------------------

    @Test
    void appDeclaredFieldOrderOverridesTheDefault(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);
        JsonNode appModelJson = MAPPER.readTree("""
                {
                  "metadata": {
                    "fieldOrder": {
                      "clinicbase::Patient": ["specialty", "licenseNumber", "id", "name"]
                    }
                  }
                }
                """);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelJson);

        CompiledConcept patient = result.model().findConcept("clinicbase::Patient").orElseThrow();
        assertEquals(List.of("specialty", "licenseNumber", "id", "name"), fieldNames(patient),
                "expected the app's own declared order to win over base-then-extension declaration order");
    }

    @Test
    void appOrderOmittingAFieldAppendsItAtTheEndRatherThanDroppingIt(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);
        // The app only bothers to place "specialty" up front; it never mentions "licenseNumber" --
        // simulating an app whose model.json predates clinicext2 being added to the composition.
        JsonNode appModelJson = MAPPER.readTree("""
                {
                  "metadata": { "fieldOrder": { "clinicbase::Patient": ["specialty", "id", "name"] } }
                }
                """);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelJson);

        CompiledConcept patient = result.model().findConcept("clinicbase::Patient").orElseThrow();
        assertEquals(List.of("specialty", "id", "name", "licenseNumber"), fieldNames(patient),
                "the un-named field must still render, appended at the end, never silently dropped");
    }

    @Test
    void unknownFieldNameInAppOrderIsIgnoredRatherThanFailing(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);
        JsonNode appModelJson = MAPPER.readTree("""
                {
                  "metadata": { "fieldOrder": { "clinicbase::Patient": ["specialtyy", "id", "name", "licenseNumber"] } }
                }
                """);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelJson);

        CompiledConcept patient = result.model().findConcept("clinicbase::Patient").orElseThrow();
        assertEquals(List.of("id", "name", "licenseNumber", "specialty"), fieldNames(patient),
                "a typo'd name is simply ignored -- the real field it presumably meant ('specialty') "
                        + "still renders, appended at the end like any other unmentioned field");
    }

    @Test
    void orderingIsDeterministicAcrossRepeatedCalls(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);
        JsonNode appModelJson = MAPPER.readTree("""
                { "metadata": { "fieldOrder": { "clinicbase::Patient": ["licenseNumber", "specialty"] } } }
                """);

        List<String> first = fieldNames(new PackExtensionComposer()
                .composeExtensionsWithOrdering(fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelJson)
                .model().findConcept("clinicbase::Patient").orElseThrow());
        List<String> second = fieldNames(new PackExtensionComposer()
                .composeExtensionsWithOrdering(fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelJson)
                .model().findConcept("clinicbase::Patient").orElseThrow());

        assertEquals(first, second, "the exact same inputs must produce the exact same field order every time");
    }

    // ---- extension field provenance (UI attribution) -------------------------------------------

    @Test
    void extensionFieldOriginsTracksOnlyFieldsAnExtensionActuallyAdded(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, null);

        Map<String, String> origins = result.extensionFieldOrigins().get("clinicbase::Patient");
        assertEquals("clinicext", origins.get("specialty"));
        assertEquals("clinicext2", origins.get("licenseNumber"));
        assertFalse(origins.containsKey("id"), "the shared id anchor was re-declared identically, not added -- no origin");
        assertFalse(origins.containsKey("name"), "a base-pack-only field must never carry extension provenance");
    }

    @Test
    void aConceptNoExtensionTouchedCarriesNoOriginsEntryAtAll(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), List.of(), null);

        assertTrue(result.extensionFieldOrigins().isEmpty());
    }

    // ---- proof through the real generator (order) ------------------------------------------------

    /**
     * The stronger proof for ORDER, mirroring {@code PackExtensionComposerTest
     * .additiveExtension_composesThroughRealGeneration} for step 1: the app-declared order is not
     * just an in-memory field list, it survives an actual {@link GeneratorFacade#generate} run and
     * shows up, in that exact order, in the emitted generated entity.
     */
    @Test
    void appDeclaredOrder_survivesRealGeneration(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);
        JsonNode appModelJson = MAPPER.readTree("""
                { "metadata": { "fieldOrder": { "clinicbase::Patient": ["licenseNumber", "specialty", "id", "name"] } } }
                """);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, appModelJson);

        Path out = Files.createTempDirectory("npdev-pack-extension-ordering-out-");
        Path migrations = Files.createTempDirectory("npdev-pack-extension-ordering-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(result.model(), out, migrations, fx.appModel);

        Path entityFile;
        try (Stream<Path> walk = Files.walk(out.resolve("src/main/java/com/npdev/generated/entities"))) {
            entityFile = walk.filter(Files::isRegularFile)
                    .filter(p -> readString(p).contains("licenseNumber"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a generated entity containing 'licenseNumber'"));
        }
        String source = readString(entityFile);
        // entity.mustache declares each field as `private {{javaType}} {{name}};` -- a space-prefixed
        // `" <fieldName>;"` marker lands on that exact declaration, not an unrelated earlier mention
        // (e.g. "name" alone would also match a `@Table(name = ...)` annotation well before any
        // field declaration).
        int licenseAt = source.indexOf(" licenseNumber;");
        int specialtyAt = source.indexOf(" specialty;");
        int idAt = source.indexOf(" id;");
        int nameAt = source.indexOf(" name;");
        assertTrue(licenseAt >= 0 && specialtyAt >= 0 && idAt >= 0 && nameAt >= 0,
                "expected every field declaration to appear in the generated entity:\n" + source);
        assertTrue(licenseAt < specialtyAt && specialtyAt < idAt && idAt < nameAt,
                "expected declared-field order licenseNumber < specialty < id < name in the generated entity:\n" + source);
    }

    // ---- proof through the real UI manifest (attribution) ----------------------------------------

    /**
     * The UI-facing half of this step: {@link BusinessUiEmitter}'s new {@code extensionFieldOrigins}
     * parameter actually reaches the generated business UI manifest a browser reads, as a per-field
     * {@code "extensionSource"} attribute -- non-blank only for the field an extension added.
     */
    @Test
    void extensionSourceAttribution_reachesTheGeneratedUiManifest(@TempDir Path tempDir) throws Exception {
        Fixtures fx = twoExtensions(tempDir);

        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, null);

        Path out = Files.createTempDirectory("npdev-pack-extension-manifest-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(result.model(), "ADMIN", new SettingResolver(SettingStore.empty()), result.extensionFieldOrigins());

        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        assertEquals("clinicext", extensionSourceOf(manifest, "specialty"),
                "expected specialty's extensionSource to be clinicext:\n" + manifest);
        assertEquals("clinicext2", extensionSourceOf(manifest, "licenseNumber"),
                "expected licenseNumber's extensionSource to be clinicext2:\n" + manifest);
        assertEquals("", extensionSourceOf(manifest, "id"),
                "a base-pack field must carry no extensionSource:\n" + manifest);
        assertEquals("", extensionSourceOf(manifest, "name"),
                "a base-pack field must carry no extensionSource:\n" + manifest);
    }

    @Test
    void threeArgEmitOverload_stillOmitsExtensionSourceEntirely_backwardCompatibility(@TempDir Path tempDir) throws Exception {
        // Regression guard: every pre-existing caller of the 3-arg emit() (415 tests worth) must see
        // byte-identical behavior -- extensionSource present but always blank, never a KeyError/NPE.
        Fixtures fx = twoExtensions(tempDir);
        PackExtensionComposer.ExtensionComposition result = new PackExtensionComposer().composeExtensionsWithOrdering(
                fx.withBase, Map.of("clinicbase", fx.unsealedBaseJson), fx.bothExtensions, null);

        Path out = Files.createTempDirectory("npdev-pack-extension-manifest-default-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(result.model(), "ADMIN", new SettingResolver(SettingStore.empty()));

        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        assertEquals("", extensionSourceOf(manifest, "specialty"));
        assertEquals("", extensionSourceOf(manifest, "licenseNumber"));
    }

    // ---- fixtures / helpers ------------------------------------------------------------------

    private record Fixtures(
            CompiledModel withBase,
            JsonNode unsealedBaseJson,
            List<PackExtensionComposer.ExtensionSource> bothExtensions,
            Path appModel
    ) {
    }

    private static Fixtures twoExtensions(Path tempDir) throws Exception {
        Path baseFile = writePack(tempDir, "clinicbase-pack.json", CLINICBASE_PACK_JSON);
        Path extFile = writePack(tempDir, "clinicext-pack.json", CLINICEXT_PACK_JSON);
        Path ext2File = writePack(tempDir, "clinicext2-pack.json", CLINICEXT2_PACK_JSON);

        BuiltinPackComposer builtinComposer = new BuiltinPackComposer();
        List<CompiledConcept> baseConcepts = builtinComposer.loadPackConcepts(baseFile, "clinicbase");
        List<CompiledConcept> ext1Concepts = builtinComposer.loadPackConcepts(extFile, "clinicext");
        List<CompiledConcept> ext2Concepts = builtinComposer.loadPackConcepts(ext2File, "clinicext2");
        CompiledConcept ext1Patient = findConcept(ext1Concepts, "clinicext::Patient");
        CompiledConcept ext2Patient = findConcept(ext2Concepts, "clinicext2::Patient");
        JsonNode ext1RawJson = MAPPER.readTree(extFile.toFile());
        JsonNode ext2RawJson = MAPPER.readTree(ext2File.toFile());
        JsonNode unsealedBaseJson = markUnsealed(MAPPER.readTree(baseFile.toFile()));

        Path appModel = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
        ModelAst ast = new JsonModelParser().parse(appModel);
        CompiledModel app = new ModelCompiler().compile(ast);
        CompiledModel withBase = builtinComposer.merge(app, baseConcepts);

        List<PackExtensionComposer.ExtensionSource> bothExtensions = List.of(
                new PackExtensionComposer.ExtensionSource("clinicext", ext1RawJson, ext1Patient),
                new PackExtensionComposer.ExtensionSource("clinicext2", ext2RawJson, ext2Patient));

        return new Fixtures(withBase, unsealedBaseJson, bothExtensions, appModel);
    }

    private static Path writePack(Path dir, String fileName, String json) throws Exception {
        Path file = dir.resolve(fileName);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static CompiledConcept findConcept(List<CompiledConcept> concepts, String qualifiedName) {
        return concepts.stream()
                .filter(c -> qualifiedName.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected concept '" + qualifiedName + "' among " + concepts));
    }

    /** Same trick {@code PackExtensionComposerTest} uses -- see that class's own javadoc for why. */
    private static JsonNode markUnsealed(JsonNode packJson) {
        ObjectNode copy = ((ObjectNode) packJson).deepCopy();
        copy.putArray("packs").addObject().put("pack", "unrelated-dependency").put("version", "1.0.0");
        return copy;
    }

    private static List<String> fieldNames(CompiledConcept concept) {
        return concept.getFields().stream().map(CompiledField::getName).toList();
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Cheap, dependency-free extraction, same style as BusinessUiEmitterFieldWidgetCascadeTest:
     *  find the field object by name, then its extensionSource value. */
    private static String extensionSourceOf(String manifest, String fieldName) {
        String marker = "\"name\" : \"" + fieldName + "\"";
        int fieldStart = manifest.indexOf(marker);
        assertTrue(fieldStart >= 0, "field \"" + fieldName + "\" not found in manifest:\n" + manifest);
        int key = manifest.indexOf("\"extensionSource\"", fieldStart);
        assertTrue(key >= 0, "extensionSource key not found after field \"" + fieldName + "\":\n" + manifest);
        int valueStart = manifest.indexOf('"', manifest.indexOf(':', key) + 1) + 1;
        int valueEnd = manifest.indexOf('"', valueStart);
        return manifest.substring(valueStart, valueEnd);
    }
}
