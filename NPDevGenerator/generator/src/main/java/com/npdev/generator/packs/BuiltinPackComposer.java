package com.npdev.generator.packs;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Composes built-in NPDev platform packs (e.g. identity, workspace) into a generated app.
 *
 * <p>Platform packs live under {@code NPDevContract/packs}, outside any app's model root, so they
 * cannot be pulled in with an ordinary relative {@code $ref} — {@link ModelSourceResolver} forbids
 * refs that escape the model root (a deliberate safety invariant). This composer instead compiles
 * each pack in isolation via a throwaway staging model and returns its compiled concepts (prefixed
 * with the pack alias, e.g. {@code identity::User}), which {@link #merge} folds into the app model so
 * they are emitted as real tables/entities/CRUD.</p>
 */
public final class BuiltinPackComposer {

    /** Loads and compiles the concepts contributed by a single built-in pack file. */
    public List<CompiledConcept> loadPackConcepts(Path packFile, String alias) {
        if (packFile == null || !Files.isRegularFile(packFile)) {
            throw new IllegalArgumentException("Built-in pack file not found: " + packFile);
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }

        Path staging = null;
        try {
            staging = Files.createTempDirectory("npdev-builtin-pack-" + alias + "-");
            Path packCopyDir = Files.createDirectories(staging.resolve("packs").resolve(alias));
            Files.writeString(packCopyDir.resolve("pack.json"),
                    Files.readString(packFile, StandardCharsets.UTF_8), StandardCharsets.UTF_8);

            Path stagingModel = staging.resolve("model.json");
            Files.writeString(stagingModel, stagingModelJson(alias), StandardCharsets.UTF_8);

            ResolvedModelSource resolved = new ModelSourceResolver().resolve(stagingModel);
            ModelAst ast = new JsonModelParser().parse(resolved);
            CompiledModel compiled = new ModelCompiler().compile(ast);

            String prefix = alias + "::";
            List<CompiledConcept> concepts = new ArrayList<>();
            for (CompiledConcept concept : compiled.getConcepts()) {
                if (concept.getName() != null && concept.getName().startsWith(prefix)) {
                    concepts.add(concept);
                }
            }
            return List.copyOf(concepts);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to compose built-in pack '" + alias + "' from " + packFile, exception);
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * Returns a copy of {@code app} with {@code extraConcepts} added. Existing concepts win on name
     * collision (the app keeps precedence over a built-in of the same name); all other catalogs are
     * carried over unchanged.
     */
    public CompiledModel merge(CompiledModel app, List<CompiledConcept> extraConcepts) {
        Objects.requireNonNull(app, "app");
        LinkedHashMap<String, CompiledConcept> byName = new LinkedHashMap<>();
        for (CompiledConcept concept : app.getConcepts()) {
            byName.put(concept.getName(), concept);
        }
        if (extraConcepts != null) {
            for (CompiledConcept concept : extraConcepts) {
                if (concept != null && concept.getName() != null) {
                    byName.putIfAbsent(concept.getName(), concept);
                }
            }
        }
        return new CompiledModel(
                app.getNamespace(),
                app.getDslVersion(),
                app.getVersion(),
                byName,
                app.getDomainTypes(),
                app.getCapabilities(),
                app.getBindings(),
                app.getEvents(),
                app.getFlows(),
                app.getOrchestrationRules(),
                app.getQueries(),
                app.getRuleProfiles(),
                app.getProcedures(),
                app.getPanels()
        );
    }

    private static String stagingModelJson(String alias) {
        return "{\n"
                + "  \"namespace\": \"npdev.builtin." + alias + "\",\n"
                + "  \"dslVersion\": \"1.0.0\",\n"
                + "  \"version\": \"1.0\",\n"
                + "  \"packs\": [ { \"$ref\": \"packs/" + alias + "/pack.json\", \"as\": \"" + alias + "\" } ],\n"
                + "  \"concepts\": [ { \"name\": \"BuiltinComposeRoot\", \"fields\": ["
                + " { \"name\": \"id\", \"type\": \"uuid\", \"id\": true, \"required\": true } ] } ]\n"
                + "}\n";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of the staging directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of the staging directory
        }
    }
}
