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
import com.npdev.generator.api.GeneratorFacade;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-10 steps 2-3: live proof for {@link PackExtensionComposer} -- an extension pack additively
 * patching a nullable field onto a base pack's own concept, hard refusal on a field-shape collision,
 * and refusal to extend a sealed pack.
 *
 * <p><b>Why the successful-merge test does not use a real repo pack as its base.</b> The task asked
 * for a real pack ({@code identity}/{@code workspace}) as base wherever possible. Checked: every real
 * pack in this repo ({@code identity}, {@code workspace}, {@code project-tracker-demo}) is a LEAF pack
 * declaring no {@code packs[]} of its own and no unbound {@code requires.capabilities} -- so all three
 * certify SEALED under {@code PackSealednessAnalyzer.analyze}, and this composer correctly refuses to
 * extend any of them (see {@link #refusesExtension_ofRealSealedIdentityPack}, which DOES use the real
 * identity pack for exactly that reason). There is therefore no real pack in this repo an additive
 * extension could ever succeed against; the successful-merge test below uses a small synthetic
 * "clinicbase" pack instead, compiled through the exact same real {@link JsonModelParser}/{@link
 * ModelCompiler} pipeline {@link BuiltinPackComposer} uses for identity/workspace -- nothing about the
 * compile path itself is faked, only the base pack's content.
 */
class PackExtensionComposerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path IDENTITY_PACK_FILE =
            Path.of("..", "..", "NPDevContract", "packs", "identity", "pack.json").toAbsolutePath().normalize();

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

    // ---- metadata.extends reading -------------------------------------------------------------

    @Test
    void readsExtensionTargetFromPackMetadata() throws Exception {
        JsonNode packJson = MAPPER.readTree(CLINICEXT_PACK_JSON);
        PackExtensionComposer.ExtensionTarget target = new PackExtensionComposer().readExtensionTarget(packJson);

        assertEquals("clinicbase", target.packAlias());
        assertEquals("Patient", target.conceptName());
        assertEquals("clinicbase::Patient", target.qualifiedName());
    }

    @Test
    void readsExtensionTargetFromFirstClassExtendsKeyword() throws Exception {
        String firstClassExtends = """
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinicext",
                  "version": "1.0.0",
                  "extends": { "pack": "clinicbase", "concept": "Patient" },
                  "concepts": [
                    { "name": "Patient", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "specialty", "type": "string", "required": false, "maxLength": 80 }
                    ]}
                  ]
                }
                """;
        JsonNode packJson = MAPPER.readTree(firstClassExtends);
        PackExtensionComposer.ExtensionTarget target = new PackExtensionComposer().readExtensionTarget(packJson);

        assertEquals("clinicbase", target.packAlias());
        assertEquals("Patient", target.conceptName());
        assertEquals("clinicbase::Patient", target.qualifiedName());
    }

    @Test
    void firstClassExtendsTakesPrecedenceOverMetadataExtends() throws Exception {
        String bothExtends = """
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinicext",
                  "version": "1.0.0",
                  "extends": { "pack": "firstclass", "concept": "Alpha" },
                  "metadata": { "extends": { "pack": "legacy", "concept": "Beta" } },
                  "concepts": [
                    { "name": "Patient", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ]}
                  ]
                }
                """;
        JsonNode packJson = MAPPER.readTree(bothExtends);
        PackExtensionComposer.ExtensionTarget target = new PackExtensionComposer().readExtensionTarget(packJson);

        assertEquals("firstclass", target.packAlias());
        assertEquals("Alpha", target.conceptName());
    }

    @Test
    void returnsNullWhenPackDeclaresNoExtensionTarget() throws Exception {
        JsonNode packJson = MAPPER.readTree(CLINICBASE_PACK_JSON);
        assertNull(new PackExtensionComposer().readExtensionTarget(packJson));
    }

    // ---- step 1: additive-only in-place extension composes -------------------------------------

    @Test
    void additivelyMergesNullableFieldFromExtensionPack(@TempDir Path tempDir) throws Exception {
        Path baseFile = writePack(tempDir, "clinicbase-pack.json", CLINICBASE_PACK_JSON);
        Path extFile = writePack(tempDir, "clinicext-pack.json", CLINICEXT_PACK_JSON);

        CompiledConcept basePatient = loadConcept(baseFile, "clinicbase", "clinicbase::Patient");
        CompiledConcept extPatient = loadConcept(extFile, "clinicext", "clinicext::Patient");

        JsonNode unsealedBaseJson = markUnsealed(MAPPER.readTree(baseFile.toFile()));

        CompiledConcept merged = new PackExtensionComposer()
                .applyExtension("clinicbase", unsealedBaseJson, basePatient, "clinicext", extPatient);

        assertEquals("clinicbase::Patient", merged.getName());
        assertEquals(3, merged.getFields().size(),
                "expected id + name (base) + specialty (extension) = 3 fields, got: " + fieldNames(merged));
        assertTrue(hasField(merged, "id"));
        assertTrue(hasField(merged, "name"));
        CompiledField specialty = fieldNamed(merged, "specialty");
        assertFalse(specialty.isRequired(), "an additively-merged field must be nullable");
    }

    /**
     * The stronger proof: the merged concept is not just an in-memory object, it actually composes
     * through the real generator pipeline (same one {@code BuiltinPackComposerTest} uses for
     * identity/workspace) and the extension's field shows up in the emitted Java source.
     */
    @Test
    void additiveExtension_composesThroughRealGeneration(@TempDir Path tempDir) throws Exception {
        Path baseFile = writePack(tempDir, "clinicbase-pack.json", CLINICBASE_PACK_JSON);
        Path extFile = writePack(tempDir, "clinicext-pack.json", CLINICEXT_PACK_JSON);

        BuiltinPackComposer builtinComposer = new BuiltinPackComposer();
        List<CompiledConcept> baseConcepts = builtinComposer.loadPackConcepts(baseFile, "clinicbase");
        List<CompiledConcept> extConcepts = builtinComposer.loadPackConcepts(extFile, "clinicext");
        CompiledConcept extPatient = findConcept(extConcepts, "clinicext::Patient");
        JsonNode extRawJson = MAPPER.readTree(extFile.toFile());
        JsonNode unsealedBaseJson = markUnsealed(MAPPER.readTree(baseFile.toFile()));

        Path appModel = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
        ModelAst ast = new JsonModelParser().parse(appModel);
        CompiledModel app = new ModelCompiler().compile(ast);
        CompiledModel withBase = builtinComposer.merge(app, baseConcepts);

        PackExtensionComposer extensionComposer = new PackExtensionComposer();
        CompiledModel withExtension = extensionComposer.composeExtensions(
                withBase,
                Map.of("clinicbase", unsealedBaseJson),
                List.of(new PackExtensionComposer.ExtensionSource("clinicext", extRawJson, extPatient)));

        assertTrue(withExtension.findConcept("clinicbase::Patient").isPresent());
        assertTrue(hasField(withExtension.findConcept("clinicbase::Patient").get(), "specialty"));
        assertTrue(withExtension.findConcept("clinicext::Patient").isEmpty(),
                "the extension pack's own standalone concept must never appear in the composed model -- "
                        + "it exists only to carry the patch");

        Path out = Files.createTempDirectory("npdev-pack-extension-out-");
        Path migrations = Files.createTempDirectory("npdev-pack-extension-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(withExtension, out, migrations, appModel);

        boolean specialtyEmitted;
        try (Stream<Path> walk = Files.walk(out.resolve("src/main/java/com/npdev/generated/entities"))) {
            specialtyEmitted = walk.filter(Files::isRegularFile)
                    .anyMatch(p -> readString(p).contains("specialty"));
        }
        assertTrue(specialtyEmitted,
                "expected the extension's additive 'specialty' field to appear in a generated entity");
    }

    // ---- step 2: hard refusal on collision ------------------------------------------------------

    @Test
    void refusesExtension_whenFieldCollidesWithDifferentShape(@TempDir Path tempDir) throws Exception {
        Path baseFile = writePack(tempDir, "clinicbase-pack.json", CLINICBASE_PACK_JSON);
        Path extFile = writePack(tempDir, "clinicext-conflict-pack.json", """
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

        CompiledConcept basePatient = loadConcept(baseFile, "clinicbase", "clinicbase::Patient");
        CompiledConcept extPatient = loadConcept(extFile, "clinicext", "clinicext::Patient");
        JsonNode unsealedBaseJson = markUnsealed(MAPPER.readTree(baseFile.toFile()));

        PackExtensionRefusedException thrown = assertThrows(PackExtensionRefusedException.class,
                () -> new PackExtensionComposer()
                        .applyExtension("clinicbase", unsealedBaseJson, basePatient, "clinicext", extPatient));

        assertTrue(thrown.getMessage().contains("clinicbase"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("clinicext"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("name"), thrown.getMessage());
    }

    @Test
    void refusesExtension_whenNewFieldIsRequired(@TempDir Path tempDir) throws Exception {
        Path baseFile = writePack(tempDir, "clinicbase-pack.json", CLINICBASE_PACK_JSON);
        Path extFile = writePack(tempDir, "clinicext-required-pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinicext",
                  "version": "1.0.0",
                  "metadata": { "extends": { "pack": "clinicbase", "concept": "Patient" } },
                  "concepts": [
                    { "name": "Patient", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "mandatoryThing", "type": "string", "required": true }
                    ]}
                  ]
                }
                """);

        CompiledConcept basePatient = loadConcept(baseFile, "clinicbase", "clinicbase::Patient");
        CompiledConcept extPatient = loadConcept(extFile, "clinicext", "clinicext::Patient");
        JsonNode unsealedBaseJson = markUnsealed(MAPPER.readTree(baseFile.toFile()));

        PackExtensionRefusedException thrown = assertThrows(PackExtensionRefusedException.class,
                () -> new PackExtensionComposer()
                        .applyExtension("clinicbase", unsealedBaseJson, basePatient, "clinicext", extPatient));

        assertTrue(thrown.getMessage().contains("mandatoryThing"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("required"), thrown.getMessage());
    }

    // ---- step 3: extending a sealed pack refuses ------------------------------------------------

    @Test
    void refusesExtension_ofRealSealedIdentityPack() throws Exception {
        assertTrue(Files.isRegularFile(IDENTITY_PACK_FILE), "expected " + IDENTITY_PACK_FILE + " to exist");
        JsonNode identityJson = MAPPER.readTree(IDENTITY_PACK_FILE.toFile());

        CompiledConcept identityUser = loadConcept(IDENTITY_PACK_FILE, "identity", "identity::User");

        JsonNode clinicalPackJson = MAPPER.readTree("""
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinical",
                  "version": "1.0.0",
                  "metadata": { "extends": { "pack": "identity", "concept": "User" } },
                  "concepts": [
                    { "name": "User", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "specialty", "type": "string", "required": false }
                    ]}
                  ]
                }
                """);
        CompiledConcept clinicalUser = new CompiledConcept(
                "clinical::User", "ClinicalUser", "clinical_users",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("specialty", "string", "java.lang.String", false, false, false)));

        PackExtensionRefusedException thrown = assertThrows(PackExtensionRefusedException.class,
                () -> new PackExtensionComposer()
                        .applyExtension("identity", identityJson, identityUser, "clinical", clinicalUser));

        assertTrue(thrown.getMessage().contains("identity"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("clinical"), thrown.getMessage());
        assertTrue(thrown.getMessage().toLowerCase(java.util.Locale.ROOT).contains("sealed"), thrown.getMessage());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Path writePack(Path dir, String fileName, String json) throws Exception {
        Path file = dir.resolve(fileName);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static CompiledConcept loadConcept(Path packFile, String alias, String qualifiedName) {
        return findConcept(new BuiltinPackComposer().loadPackConcepts(packFile, alias), qualifiedName);
    }

    private static CompiledConcept findConcept(List<CompiledConcept> concepts, String qualifiedName) {
        return concepts.stream()
                .filter(c -> qualifiedName.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected concept '" + qualifiedName + "' among " + concepts));
    }

    /** Injects a dummy transitive {@code packs[]} dependency so {@code PackSealednessAnalyzer} certifies
     *  this pack UNSEALED for the purposes of a test -- see the class doc for why this is necessary
     *  (every real pack in this repo is a leaf pack and therefore sealed). Never affects compilation:
     *  this mutates a JsonNode already parsed from the real, cleanly-compilable pack file, it does not
     *  touch the file the compile pipeline actually reads. */
    private static JsonNode markUnsealed(JsonNode packJson) {
        ObjectNode copy = ((ObjectNode) packJson).deepCopy();
        copy.putArray("packs").addObject().put("pack", "unrelated-dependency").put("version", "1.0.0");
        return copy;
    }

    private static boolean hasField(CompiledConcept concept, String fieldName) {
        return concept.getFields().stream().anyMatch(f -> fieldName.equals(f.getName()));
    }

    private static CompiledField fieldNamed(CompiledConcept concept, String fieldName) {
        return concept.getFields().stream()
                .filter(f -> fieldName.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected field '" + fieldName + "' among " + fieldNames(concept)));
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
}
