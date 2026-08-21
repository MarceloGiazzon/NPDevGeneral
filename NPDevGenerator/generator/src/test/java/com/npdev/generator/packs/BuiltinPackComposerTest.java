package com.npdev.generator.packs;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.kernel.abi.KernelAbi;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinPackComposerTest {

    private static Path packsDir() {
        return Path.of("..", "..", "NPDevContract", "packs").normalize();
    }

    @Test
    void loadsConceptsFromRealBuiltinPacks() {
        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> identity =
                composer.loadPackConcepts(packsDir().resolve("identity").resolve("pack.json"), "identity");
        List<CompiledConcept> workspace =
                composer.loadPackConcepts(packsDir().resolve("workspace").resolve("pack.json"), "workspace");

        assertTrue(identity.stream().anyMatch(c -> "identity::User".equals(c.getName())));
        assertTrue(identity.stream().anyMatch(c -> "identity::Role".equals(c.getName())));
        assertTrue(identity.stream().anyMatch(c -> "identity::UserRole".equals(c.getName())));
        assertTrue(workspace.stream().anyMatch(c -> "workspace::Menu".equals(c.getName())));
        // RC-A2 (Move 14 Phase B item B1): Preference was retired in favor of PropertyValue -- see
        // BREAKING.md's 2026-08-02 entry and WorkspacePackResolutionTest's identical fix (dsl module).
        assertTrue(workspace.stream().anyMatch(c -> "workspace::PropertyValue".equals(c.getName())));
    }

    @Test
    void mergedBuiltinTablesAreEmittedAsEntities() throws Exception {
        Path appModel = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
        ModelAst ast = new JsonModelParser().parse(appModel);
        CompiledModel app = new ModelCompiler().compile(ast);

        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> builtin = new ArrayList<>();
        builtin.addAll(composer.loadPackConcepts(packsDir().resolve("identity").resolve("pack.json"), "identity"));
        builtin.addAll(composer.loadPackConcepts(packsDir().resolve("workspace").resolve("pack.json"), "workspace"));
        CompiledModel merged = composer.merge(app, builtin);

        assertTrue(merged.findConcept("identity::User").isPresent());
        assertTrue(merged.findConcept("workspace::Menu").isPresent());

        Path out = Files.createTempDirectory("npdev-compose-out-");
        Path migrations = Files.createTempDirectory("npdev-compose-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(merged, out, migrations, appModel);

        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/entities/IdentityUser.java")),
                "Composed identity::User should be emitted as an entity");
        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/entities/WorkspaceMenu.java")),
                "Composed workspace::Menu should be emitted as an entity");

        // The pack alias must be folded into a readable category label, not leaked as "identity::Users".
        String manifest = Files.readString(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        assertTrue(manifest.contains("Identity Users"),
                "Composed identity::User should display as 'Identity Users'");
        assertTrue(manifest.contains("Workspace Menus"),
                "Composed workspace::Menu should display as 'Workspace Menus'");
        assertFalse(manifest.contains("identity::Users"),
                "Display label should not leak the pack alias prefix");

        // Super-user admin UI support: internal tables marked admin, app concepts not, role surfaced.
        assertTrue(manifest.contains("\"superUserRole\""),
                "manifest should carry the super-user role");
        assertTrue(manifest.contains("\"admin\" : true"),
                "composed internal tables should be marked admin");
        assertTrue(manifest.contains("\"admin\" : false"),
                "app concepts should not be marked admin");
        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/controllers/GeneratedMeController.java")),
                "the /api/me roles controller should be emitted");
    }

    /**
     * BUILD-2 (BT-2's own "the linking" follow-on, ledger item BUILD-2): the OTHER half of "no
     * identity sources in its tree" (the assembly half is {@code
     * FinalAppAssemblerTest.linksASealedPackJar_...}) -- proves {@code GeneratorFacade}'s {@code
     * linkedSealedPacks} parameter suppresses ONLY the linked pack's ENTITY (which already exists in
     * the sealed jar), while its DTO/service/controller ARE still generated -- referencing the
     * sealed jar's real package/class name -- so the concept stays reachable over HTTP. An UNLINKED
     * composed pack (workspace, here) is completely unaffected, using the SAME real merged model
     * {@link #mergedBuiltinTablesAreEmittedAsEntities} already proves emits {@code IdentityUser.java}
     * when nothing is linked.
     *
     * <p>Corrects a REAL pre-existing bug in this test's own prior version (found during this
     * session's investigation, not asserted by any prior session): the old assertion checked for a
     * file named {@code IdentityUserDto.java}, which {@code DtoEmitter} has never emitted under any
     * circumstance (it emits {@code *CreateRequest}/{@code *UpdateRequest}/{@code *Response}) -- so
     * that {@code assertFalse} was vacuously true regardless of whether DTOs were actually
     * suppressed, proving nothing.
     */
    @Test
    void linkedSealedPackConceptsAreNotGeneratedAsAppOwnedSources() throws Exception {
        Path appModel = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
        ModelAst ast = new JsonModelParser().parse(appModel);
        CompiledModel app = new ModelCompiler().compile(ast);

        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> builtin = new ArrayList<>();
        builtin.addAll(composer.loadPackConcepts(packsDir().resolve("identity").resolve("pack.json"), "identity"));
        builtin.addAll(composer.loadPackConcepts(packsDir().resolve("workspace").resolve("pack.json"), "workspace"));
        CompiledModel merged = composer.merge(app, builtin);

        PackAbiManifest identityManifest = new PackAbiManifest("identity", "1.0.0", 1, KernelAbi.CURRENT_ABI_VERSION);
        LinkedSealedPack identityLink = new LinkedSealedPack("identity", identityManifest);

        Path out = Files.createTempDirectory("npdev-compose-linked-out-");
        Path migrations = Files.createTempDirectory("npdev-compose-linked-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(merged, out, migrations, appModel, List.of(identityLink));

        assertFalse(Files.exists(out.resolve("src/main/java/com/npdev/generated/entities/IdentityUser.java")),
                "identity is linked as a sealed pack -- its entity must not be generated into this app's tree");

        // The REST-layer classes ARE generated (app-owned naming unchanged: IdentityUser*), but must
        // reference the sealed jar's real package + bare class name, not the app's own default.
        Path serviceBase = out.resolve("src/main/java/com/npdev/generated/services/IdentityUserServiceBase.java");
        assertTrue(Files.exists(serviceBase), "identity is linked -- a service must still be generated so it is reachable");
        String serviceSource = Files.readString(serviceBase);
        assertTrue(serviceSource.contains("import com.npdev.pack.identity.v1.User;"),
                "the linked service must import the sealed jar's real entity class");
        assertFalse(serviceSource.contains("import com.npdev.generated.entities.IdentityUser;"),
                "the linked service must NOT import the app's own (non-existent) default entity");
        assertTrue(serviceSource.contains("new User()"),
                "the linked service must instantiate the sealed jar's real entity type");

        Path controllerBase = out.resolve("src/main/java/com/npdev/generated/controllers/IdentityUserControllerBase.java");
        assertTrue(Files.exists(controllerBase), "identity is linked -- a controller must still be generated so it is reachable");
        String controllerSource = Files.readString(controllerBase);
        assertTrue(controllerSource.contains("import com.npdev.pack.identity.v1.User;"),
                "the linked controller must import the sealed jar's real entity class");

        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/dtos/IdentityUserCreateRequest.java")),
                "identity is linked -- its create-request DTO must still be generated (plain POJO, no entity import)");
        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/dtos/IdentityUserResponse.java")),
                "identity is linked -- its response DTO must still be generated");

        // workspace was NOT linked -- completely unaffected by identity's exclusion.
        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/entities/WorkspaceMenu.java")),
                "an unlinked composed pack (workspace) must still generate its own entity normally");
        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/services/WorkspaceMenuServiceBase.java")),
                "an unlinked composed pack's service must reference its own normal entity type");
        assertTrue(Files.readString(out.resolve("src/main/java/com/npdev/generated/services/WorkspaceMenuServiceBase.java"))
                        .contains("import com.npdev.generated.entities.WorkspaceMenu;"),
                "an unlinked concept's service must still import the app's own default entity package");

        // RuntimeApiEmitter must see the FULL model (not the app-owned-only one) -- otherwise a
        // linked concept's controller exists but every request 403s, because its CRUD permission
        // grants are missing from the manifest StaticPermissionEvaluator reads at boot.
        String permissions = Files.readString(out.resolve("src/main/resources/npdev/security/dev.permissions.json"));
        assertTrue(permissions.contains("\"create:identity::user\""),
                "identity::User's CRUD permission grants must exist even when its Java sources are linked, not generated");

        // The runtime's own model catalog (compiled-model.json, what NPDevModelProvider serves the
        // kernel at boot) must ALSO still carry identity::User -- GeneratedCrudRuntimeSupport.
        // requireEntity() throws "Unknown entity for runtime support" for any concept missing here,
        // regardless of whether a controller/service class exists for it.
        String compiledModelJson = Files.readString(out.resolve("src/main/resources/npdev/compiled-model.json"));
        assertTrue(compiledModelJson.contains("identity::User"),
                "the runtime's own compiled-model.json must still carry a linked concept's metadata");

        // Schema realization is NOT filtered -- a linked pack's physical table still needs to exist
        // for JPA to talk to (see the emitters' own overload doc for why this is deliberate).
        Path schemaRealizationDir = out.resolve("src/main/resources/db/schema-realization");
        boolean identityTableStillRealized;
        try (var stream = Files.walk(schemaRealizationDir)) {
            identityTableStillRealized = stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        try {
                            return Files.readString(p).contains("identity_v1_users");
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    });
        }
        assertTrue(identityTableStillRealized,
                "identity's own physical table DDL must still be realized even though its Java sources are not");
    }
}
