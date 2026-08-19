package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.packs.BuiltinPackComposer;
import com.npdev.generator.packs.PackExtensionRefusedException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-1: {@code PackExtensionComposer.composeExtensionsWithOrdering} was fully unit-tested (PACK-10
 * steps 1-4) but nothing in the REAL generation pipeline ever called it -- an app declaring an
 * extension pack in config.json's {@code packs.included} could not actually have it apply. This
 * class proves {@link GeneratorMain#composeInstalledPacksAndExtensions} -- the wiring this gap's fix
 * added inside the real {@code GeneratorMain.main()} pipeline -- through the same kind of real-
 * generation proof {@code PackExtensionComposerTest}/{@code PackExtensionComposerOrderingTest} used
 * for the composer itself: a declared extension's field must show up in {@link GeneratorFacade
 * #generate} output, and a refusal (collision, sealed target) must fire from this same real path.
 *
 * <p><b>Why the base pack is simulated as a built-in, not loaded via {@code packs.included} itself.</b>
 * Every real pack in this repo is sealed (see {@code PackExtensionComposerTest}'s own class doc), and
 * {@link BuiltinPackComposer#loadPackConcepts} stages a pack through a real {@code JsonModelParser}/
 * {@code ModelCompiler} pass with no {@code provides} at all -- so a pack whose OWN {@code pack.json}
 * declares a real {@code packs[]}/{@code requires.capabilities} entry (the only two ways to be
 * unsealed) fails to COMPILE through that same staging, confirmed empirically before writing this
 * test. The success fixtures below therefore compose {@code clinicbase} the same way {@link
 * GeneratorMain#composeInstalledPacksAndExtensions} composes a genuinely BUILT-IN base pack (real,
 * clean compile) and separately supply a doctored, never-compiled copy of its raw JSON for the
 * sealedness check -- the exact convention {@code PackExtensionComposerTest}'s {@code markUnsealed}
 * already established, reused here rather than reinvented. This reproduces the REAL two-phase shape
 * {@code GeneratorMain.main()} runs (built-in/base packs first, {@code packs.included} extensions
 * second) exactly -- only the base pack's realism is swapped, because no real one qualifies.
 */
class GeneratorMainPackExtensionCompositionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CANONICAL_DEMO_MODEL =
            Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

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

    @Test
    void anExtensionPackDeclaredInInstalledAliasesAppliesThroughTheRealPipeline(@TempDir Path tempDir) throws Exception {
        Path packsDir = Files.createDirectory(tempDir.resolve("packs"));
        Path baseFile = writePack(packsDir, "clinicbase", CLINICBASE_PACK_JSON);
        writePack(packsDir, "clinicext", CLINICEXT_PACK_JSON);

        CompiledModel withBase = composeSyntheticBase(baseFile);
        Map<String, JsonNode> basePackJsonByAlias =
                Map.of("clinicbase", markUnsealed(MAPPER.readTree(baseFile.toFile())));

        GeneratorMain.ComposedInstalledPacks result = GeneratorMain.composeInstalledPacksAndExtensions(
                withBase, List.of("clinicext"), packsDir, basePackJsonByAlias, CANONICAL_DEMO_MODEL);

        CompiledConcept patient = result.model().findConcept("clinicbase::Patient").orElseThrow();
        assertTrue(hasField(patient, "specialty"), "expected the extension's additive field in the model: " + patient);
        assertTrue(result.model().findConcept("clinicext::Patient").isEmpty(),
                "the extension pack's own standalone concept must never appear in the composed model");
        assertEquals("clinicext", result.extensionFieldOrigins().get("clinicbase::Patient").get("specialty"));

        // The stronger proof, mirroring PackExtensionComposerTest.additiveExtension_composesThroughRealGeneration:
        // the composed model actually generates, through GeneratorFacade.generate() itself (not just an
        // in-memory composer call) -- and the field shows up in the emitted Java entity source.
        Path out = Files.createTempDirectory("npdev-gap1-out-");
        Path migrations = Files.createTempDirectory("npdev-gap1-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(result.model(), out, migrations, CANONICAL_DEMO_MODEL, List.of(), result.extensionFieldOrigins());

        Path entityFile;
        try (Stream<Path> walk = Files.walk(out.resolve("src/main/java/com/npdev/generated/entities"))) {
            entityFile = walk.filter(Files::isRegularFile)
                    .filter(p -> readString(p).contains("specialty"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a generated entity containing 'specialty'"));
        }
        assertTrue(readString(entityFile).contains("specialty;"),
                "expected the extension's additive field to be a real declared entity field");

        // GAP-1's OTHER half: extensionFieldOrigins reaching the generated UI manifest through
        // GeneratorFacade itself (not a hand-rolled BusinessUiEmitter call, which is how the ordering
        // step's own test proved this before GeneratorFacade carried the parameter at all).
        String manifest = Files.readString(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        assertEquals("clinicext", extensionSourceOf(manifest, "specialty"),
                "expected the real GeneratorFacade.generate() pipeline to thread extensionFieldOrigins "
                        + "into the manifest, not just a direct BusinessUiEmitter call:\n" + manifest);
    }

    @Test
    void aFieldShapeCollisionRefusesThroughTheRealPipeline(@TempDir Path tempDir) throws Exception {
        Path packsDir = Files.createDirectory(tempDir.resolve("packs"));
        Path baseFile = writePack(packsDir, "clinicbase", CLINICBASE_PACK_JSON);
        writePack(packsDir, "clinicext", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinicext",
                  "version": "1.0.0",
                  "metadata": { "extends": { "pack": "clinicbase", "concept": "Patient" } },
                  "concepts": [
                    { "name": "Patient", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "name", "type": "int", "required": false }
                    ]}
                  ]
                }
                """);

        CompiledModel withBase = composeSyntheticBase(baseFile);
        Map<String, JsonNode> basePackJsonByAlias =
                Map.of("clinicbase", markUnsealed(MAPPER.readTree(baseFile.toFile())));

        PackExtensionRefusedException refusal = assertThrows(PackExtensionRefusedException.class, () ->
                GeneratorMain.composeInstalledPacksAndExtensions(
                        withBase, List.of("clinicext"), packsDir, basePackJsonByAlias, CANONICAL_DEMO_MODEL));

        assertTrue(refusal.getMessage().contains("clinicbase"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("clinicext"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("name"), refusal.getMessage());
    }

    @Test
    void extendingAGenuinelySealedBaseRefusesThroughTheRealPipeline_noDoctoring(@TempDir Path tempDir) throws Exception {
        // No markUnsealed here at all -- clinicbase's pack.json is fed to the sealedness check
        // EXACTLY as composeInstalledPacksAndExtensions reads it off disk for a real app, and it
        // declares no packs[]/requires.capabilities, so it is a real leaf pack -- genuinely sealed.
        Path packsDir = Files.createDirectory(tempDir.resolve("packs"));
        Path baseFile = writePack(packsDir, "clinicbase", CLINICBASE_PACK_JSON);
        writePack(packsDir, "clinicext", CLINICEXT_PACK_JSON);

        CompiledModel withBase = composeSyntheticBase(baseFile);

        PackExtensionRefusedException refusal = assertThrows(PackExtensionRefusedException.class, () ->
                GeneratorMain.composeInstalledPacksAndExtensions(
                        withBase, List.of("clinicext"), packsDir, Map.of("clinicbase", MAPPER.readTree(baseFile.toFile())),
                        CANONICAL_DEMO_MODEL));

        assertTrue(refusal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("sealed"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("clinicbase"), refusal.getMessage());
    }

    @Test
    void anOrdinaryInstalledPackWithNoExtendsTargetStillComposesAsABusinessConcept(@TempDir Path tempDir) throws Exception {
        // GAP-1 must not regress the pre-existing, already-shipped packs.included behavior for a
        // pack that declares no metadata.extends at all.
        Path packsDir = Files.createDirectory(tempDir.resolve("packs"));
        writePack(packsDir, "clinicbase", CLINICBASE_PACK_JSON);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(CANONICAL_DEMO_MODEL);
        CompiledModel app = new ModelCompiler().compile(ast);

        GeneratorMain.ComposedInstalledPacks result = GeneratorMain.composeInstalledPacksAndExtensions(
                app, List.of("clinicbase"), packsDir, Map.of(), CANONICAL_DEMO_MODEL);

        assertTrue(result.model().findConcept("clinicbase::Patient").isPresent(),
                "an ordinary (non-extending) installed pack must still compose its own standalone concept");
        assertTrue(result.extensionFieldOrigins().isEmpty());
    }

    private static CompiledModel composeSyntheticBase(Path baseFile) throws Exception {
        BuiltinPackComposer builtinComposer = new BuiltinPackComposer();
        List<CompiledConcept> baseConcepts = builtinComposer.loadPackConcepts(baseFile, "clinicbase");
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(CANONICAL_DEMO_MODEL);
        CompiledModel app = new ModelCompiler().compile(ast);
        return builtinComposer.merge(app, baseConcepts);
    }

    private static Path writePack(Path packsDir, String alias, String json) throws Exception {
        Path dir = Files.createDirectories(packsDir.resolve(alias));
        Path file = dir.resolve("pack.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /** Same trick {@code PackExtensionComposerTest}/{@code PackExtensionComposerOrderingTest} use --
     *  a doctored COPY, injected only into the sealedness-check input, never fed to compilation. */
    private static JsonNode markUnsealed(JsonNode packJson) {
        ObjectNode copy = ((ObjectNode) packJson).deepCopy();
        copy.putArray("packs").addObject().put("pack", "unrelated-dependency").put("version", "1.0.0");
        return copy;
    }

    private static boolean hasField(CompiledConcept concept, String fieldName) {
        return concept.getFields().stream().anyMatch(f -> fieldName.equals(f.getName()));
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Cheap, dependency-free extraction, same style as PackExtensionComposerOrderingTest. */
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
