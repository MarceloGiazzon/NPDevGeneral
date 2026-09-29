package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.pack.PackCache;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6 (NPDEV_MEGA_ROADMAP.md): pins the Pigmentampas sample -- the greenfield end-to-end proof app.
 * The model must parse, pass the semantic validator, compile with all seven concepts, and generate
 * without error through the real GeneratorFacade pipeline.
 */
class PigmentampasSampleTest {

    private static final Path SAMPLE_MODEL = Path.of(
            "..", "..", "NPDevSamples", "pigmentampas", "Input", "model.json").normalize();

    @BeforeAll
    static void materializePackCache() throws Exception {
        // PACK-23 pinned Pigmentampas's packs[] to a git+file:// coordinate that names whatever
        // machine originally ran `npdev pack update` -- that coordinate can never resolve anywhere
        // else, including CI, so a fresh machine's pack cache is always empty and PackCache.read()
        // throws "pack cache entry missing" before this test ever gets to exercise anything
        // (confirmed live in CI, 2026-09-29). The pack content itself IS portable -- it's checked
        // into the repo at NPDevContract/packs/<id>/pack.json, the same source publish-pack-repo.py
        // publishes from -- so populate the cache directly from there instead of depending on a git
        // fetch that can only ever work on the one machine that ran it.
        //
        // This alone isn't enough: PackDependencyGraphWalker.checkLock also requires npdev.lock's
        // committed `sourcePath` to string-match the LIVE resolved cache path, which is
        // user-home-relative (PackCache.defaultRoot()) and therefore never the same string on two
        // machines -- a committed npdev.lock can only ever satisfy checkLock on the one machine that
        // wrote it. So rewrite npdev.lock's sourcePath (and digest, in case the committed value ever
        // drifts from what this repo's own pack.json currently hashes to -- see REG-164 on why that
        // can happen even with byte-identical semantic content) to match this machine's live values
        // before parsing, the same self-healing role `npdev pack update` itself would play.
        Path packsRoot = Path.of("..", "..", "NPDevContract", "packs").normalize();
        Path lockDir = SAMPLE_MODEL.getParent();
        PackLockFile lock = PackLockFile.read(lockDir);
        Map<String, PackLockFile.LockedPack> relocked = new LinkedHashMap<>();
        for (Map.Entry<String, PackLockFile.LockedPack> entry : lock.packs().entrySet()) {
            String packId = entry.getKey();
            PackLockFile.LockedPack locked = entry.getValue();
            PackCache cache = PackCache.atDefaultRoot();
            String digestHex = cache.store(packsRoot.resolve(packId));
            String liveSourcePath = cache.entryDir(digestHex).resolve("pack.json").toString();
            relocked.put(packId, new PackLockFile.LockedPack(
                    locked.resolvedVersion(), "sha256:" + digestHex, liveSourcePath,
                    locked.migratedVersion(), locked.from()));
        }
        PackLockFile.of(relocked).write(lockDir);
    }

    @Test
    void pigmentampasModelParsesValidatesAndGenerates() throws Exception {
        assertTrue(Files.exists(SAMPLE_MODEL),
                "Expected Pigmentampas sample model at: " + SAMPLE_MODEL.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(SAMPLE_MODEL);

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);

        CompiledConcept pigment = compiled.findConcept("Pigment").orElse(null);
        assertNotNull(pigment, "Pigment concept must compile");
        // PACK-23: identity/tracing/workspace are consumed by remote coordinate (S6's own design),
        // so this counts the REAL published packs' full concept sets, not a locally-trimmed stand-in:
        // 8 domain concepts (Pigment, PigmentCategory, Supplier, PigmentSupplier, StockEntry, Order,
        // OrderItem, Credential) + identity 1.2.0 (User, Role, UserRole, PasswordResetToken,
        // UserRolePermission, ExternalIdentity = 6) + tracing 1.0.2 (TraceEntry = 1) + workspace 1.1.0
        // (Menu, PropertyValue = 2) = 17.
        assertEquals(17, compiled.getConcepts().size(),
                "Pigmentampas must compile its 8 domain concepts plus the full identity+tracing+workspace pack sets (9)");

        Path out = Files.createTempDirectory("npdev-pigmentampas-");
        Path migrations = Files.createTempDirectory("npdev-pigmentampas-migrations-");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, SAMPLE_MODEL);

        for (String entity : List.of("Pigment", "PigmentCategory", "Supplier", "PigmentSupplier",
                "StockEntry", "Order", "OrderItem")) {
            Path java = out.resolve("src/main/java/com/npdev/generated/entities/" + entity + ".java");
            assertTrue(Files.exists(java), "Expected generated entity: " + java);
        }
        Path uiManifest = out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json");
        assertTrue(Files.exists(uiManifest),
                "Expected generated business UI manifest: " + uiManifest);
        String manifest = Files.readString(uiManifest);
        for (String route : List.of("/pigments", "/categories", "/suppliers", "/pigment-suppliers",
                "/stock", "/orders", "/order-items")) {
            assertTrue(manifest.contains(route), "UI manifest must contain route " + route);
        }
    }
}