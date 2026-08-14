package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
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

/**
 * PK-6 Step 1 (PACK-ROADMAP.md card PK-6, "satellite concepts"): an extension pack declares a
 * concept with a declared 1:1 relationship to a base pack concept ({@code satelliteOf:
 * "identity::User"}) WITHOUT modifying the base pack. Loads the REAL built-in identity pack
 * (`NPDevContract/packs/identity/pack.json`), like {@link IdentityPackResolutionTest}, so the
 * shipped artifact is what actually gets extended.
 *
 * <p>Deliberately named {@code satelliteOf}, not {@code extends}: {@code concept.extends} already
 * means single-model concept inheritance (field merging via {@code ModelResolver.mergeConcept}) --
 * the PACK-ROADMAP.md card's own example text uses {@code extends}, but that keyword is already
 * load-bearing for a different mechanism, confirmed by reading {@code ConceptAst}/{@code
 * EntityAst}/{@code ModelResolver} before writing any schema change.
 */
class PackSatelliteExtensionResolutionTest {

    @TempDir
    Path temp;

    @Test
    void satellitePackAddsClinicalProfileWithoutTouchingBasePack() throws Exception {
        Path realIdentityPack = Path.of("..", "packs", "identity", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.exists(realIdentityPack), "Built-in identity pack must exist at " + realIdentityPack);
        String identityPackJson = Files.readString(realIdentityPack);

        write("packs/identity/pack.json", identityPackJson);
        write("packs/clinical/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinical",
                  "version": "1.0.0",
                  "namespace": "com.npdev.clinical",
                  "description": "PK-6 probe: satellite extension pack adding clinical fields to identity::User.",
                  "packs": [
                    { "pack": "identity", "version": "^1.0" }
                  ],
                  "concepts": [
                    {
                      "name": "UserClinicalProfile",
                      "satelliteOf": "identity::User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "userId", "type": "reference", "required": true, "unique": true,
                          "reference": { "target": "identity::User", "onDelete": "cascade" } },
                        { "name": "bloodType", "type": "string", "required": false, "maxLength": 10 }
                      ]
                    }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "pack.satellite.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/clinical/pack.json" }
                  ]
                }
                """);
        writeValidLock(Map.of(
                "identity", "packs/identity/pack.json",
                "clinical", "packs/clinical/pack.json"));

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected satellite model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept user = compiled.findConcept("identity::User").orElseThrow();
        CompiledConcept satellite = compiled.findConcept("clinical::UserClinicalProfile").orElseThrow();

        // Base pack table name is untouched -- PK-2's physical identity (packId + major), same as
        // every other consumer of the identity pack. The satellite lives in its OWN pack's table.
        assertEquals("identity_v1_users", user.getTableName());
        assertEquals("clinical_v1_user_clinical_profiles", satellite.getTableName());

        CompiledField userId = field(satellite, "userId");
        assertTrue(userId.isUnique(), "the satellite's anchor field must be unique (1:1)");
        assertTrue(userId.isRequired(), "the satellite's anchor field must be required (1:1)");
        assertEquals("identity::User", userId.getReferenceSemantics().getTarget());
        assertEquals("cascade", userId.getReferenceSemantics().getOnDelete());

        // The satelliteOf marker itself survives the full parse -> resolve -> compile chain.
        assertEquals("identity::User", satellite.getSatelliteOf());
        assertFalse(user.getFields().stream().anyMatch(f -> "bloodType".equals(f.getName())),
                "the base identity::User concept must stay untouched by the satellite extension");
    }

    @Test
    void satelliteOfMissingAnchorFieldIsRefused() throws Exception {
        Path realIdentityPack = Path.of("..", "packs", "identity", "pack.json").toAbsolutePath().normalize();
        write("packs/identity/pack.json", Files.readString(realIdentityPack));
        write("packs/clinical/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "clinical",
                  "version": "1.0.0",
                  "packs": [
                    { "pack": "identity", "version": "^1.0" }
                  ],
                  "concepts": [
                    {
                      "name": "UserClinicalProfile",
                      "satelliteOf": "identity::User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "bloodType", "type": "string", "required": false, "maxLength": 10 }
                      ]
                    }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "pack.satellite.norefused",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/clinical/pack.json" }
                  ]
                }
                """);
        writeValidLock(Map.of(
                "identity", "packs/identity/pack.json",
                "clinical", "packs/clinical/pack.json"));

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("satelliteOf") && e.contains("anchor bond")),
                "Expected a named satelliteOf anchor-bond error, got: " + errors);
    }

    @Test
    void satelliteOfRequiresPackQualifiedTarget() throws Exception {
        Path model = write("model.json", """
                {
                  "namespace": "pack.satellite.unqualified",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Base", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] },
                    { "name": "BaseProfile", "satelliteOf": "Base", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "baseId", "type": "reference", "required": true, "unique": true,
                          "reference": { "target": "Base" } }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("satelliteOf") && e.contains("pack-qualified")),
                "Expected a named satelliteOf pack-qualification error, got: " + errors);
    }

    private static CompiledField field(CompiledConcept concept, String name) {
        return concept.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow();
    }

    /** Writes a valid npdev.lock covering the given packId -> relative-source-path pairs, deriving
     *  each entry's resolvedVersion and digest fresh from the actual fixture file on disk -- same
     *  pattern as {@code PackTransitiveDependencyResolutionTest.writeValidLock}. */
    private void writeValidLock(Map<String, String> packIdToRelativeSourcePath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, PackLockFile.LockedPack> packs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : packIdToRelativeSourcePath.entrySet()) {
            Path file = temp.resolve(entry.getValue());
            String version = mapper.readTree(file.toFile()).get("version").asText();
            packs.put(entry.getKey(), new PackLockFile.LockedPack(version, PackLockFile.sha256(file), entry.getValue()));
        }
        PackLockFile.of(packs).write(temp);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
