package com.npdev.generator.packs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.pack.PackSealednessAnalyzer;
import com.npdev.dsl.v1.pack.PackVersion;
import com.npdev.generator.emitters.EntityEmitter;
import com.npdev.generator.emitters.RepositoryEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.kernel.abi.KernelAbi;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BT-2 (PACK-ROADMAP.md, Track B): "A sealed pack ships as a jar; a consuming app links it instead of
 * generating and compiling its concepts." This class is the pipeline steps 1-4 of that card's 6
 * describe, restricted to a leaf pack (no transitive {@code packs[]} of its own -- see {@link
 * com.npdev.dsl.v1.pack.PackSealednessAnalyzer}'s own doc for why).
 *
 * <p><b>Deliberately NOT wired into {@code npdev generate}'s normal app-composition path.</b> {@link
 * com.npdev.generator.packs.BuiltinPackComposer} (this package's existing sibling) is what every real
 * generated app uses today to pull {@code identity}/{@code workspace} concepts into
 * {@code com.npdev.generated.entities} -- unchanged by this class. Sealing is a SEPARATE, explicit
 * operation (a pack author publishing a jar), not something that happens as a side effect of any app's
 * own generation. This keeps BT-2's blast radius at zero for the hundreds of existing generated apps
 * and samples: nothing about normal generation changes.
 *
 * <p><b>What this produces</b> is a staged Java SOURCE tree (entity + repository classes for the
 * pack's own concepts, emitted into {@code com.npdev.pack.<packId>.v<majorVersion>}, see {@link
 * PackAbiManifest#packageName()}) plus a {@code META-INF/npdev-pack.properties} manifest -- NOT a
 * compiled jar. Compiling that source tree into a real jar (and publishing it, BT-2 step 5) is a
 * build-system operation (javac + jar, or a Gradle module) deliberately left to the caller -- this
 * class's own proof ({@code SealedPackBuilderTest}) does compile the output with an in-process
 * {@code javax.tools.JavaCompiler} to get real, diffable {@code .class} bytes, but does not assemble
 * a jar file or attempt any OCI/registry interaction (explicitly deferred, see BT-2's ledger item).
 */
public final class SealedPackBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record SealResult(
            PackAbiManifest manifest,
            List<CompiledConcept> concepts,
            Path sourceRoot
    ) {
    }

    public SealResult seal(Path packFile, Path outputSourceRoot) {
        JsonNode packJson = readJson(packFile);
        String packId = requireText(packJson, "pack", packFile);
        PackSealednessAnalyzer.SealednessResult sealedness = PackSealednessAnalyzer.analyze(packJson);
        if (!sealedness.sealed()) {
            throw new PackNotSealedException(packId, sealedness.violations());
        }

        PackVersion version = PackVersion.parse(requireText(packJson, "version", packFile));

        // Reuses the EXACT same "compile this pack alone via a throwaway staging model" mechanism
        // BuiltinPackComposer already uses for every real generated app -- sealing must see the same
        // compiled shape (types, table names, invariants) a normal app composition would, or the
        // pack could seal successfully and then behave differently when actually linked.
        List<CompiledConcept> concepts = new BuiltinPackComposer().loadPackConcepts(packFile, packId);

        PackAbiManifest manifest = new PackAbiManifest(
                packId, version.toString(), version.major(), KernelAbi.CURRENT_ABI_VERSION);
        String packageName = manifest.packageName();

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(outputSourceRoot, new RegenerationPolicy());
        EntityEmitter entityEmitter = new EntityEmitter(templates, writer);
        RepositoryEmitter repositoryEmitter = new RepositoryEmitter(templates, writer);

        // BUILD-2 (REST-layer follow-on): the SAME "qualified concept name -> bare, alias-
        // independent class name" computation a consuming app's REST-layer emitters (Service/
        // Controller) must reproduce to import the literal class this seal actually emits --
        // routed through LinkedSealedPack.bareClassName so the two can never independently drift.
        LinkedSealedPack selfLink = new LinkedSealedPack(packId, manifest);
        Map<String, CompiledConcept> conceptsByName = new LinkedHashMap<>();
        for (CompiledConcept concept : concepts) {
            conceptsByName.put(normalize(concept.getName()), concept);
        }

        for (CompiledConcept concept : concepts) {
            String className = selfLink.bareClassName(concept.getName());
            entityEmitter.emitOne(concept, className, packageName, conceptsByName);
            repositoryEmitter.emitOne(concept, className, packageName, packageName);
        }

        writeManifest(manifest, outputSourceRoot);

        return new SealResult(manifest, concepts, outputSourceRoot);
    }

    private void writeManifest(PackAbiManifest manifest, Path outputSourceRoot) {
        try {
            Path manifestPath = outputSourceRoot.resolve("META-INF").resolve("npdev-pack.properties");
            Files.createDirectories(manifestPath.getParent());
            try (OutputStream out = Files.newOutputStream(manifestPath)) {
                manifest.writeTo(out);
            }
        } catch (IOException writeError) {
            throw new UncheckedIOException(writeError);
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static JsonNode readJson(Path packFile) {
        try {
            return MAPPER.readTree(packFile.toFile());
        } catch (IOException readError) {
            throw new UncheckedIOException("failed to read pack file: " + packFile, readError);
        }
    }

    private static String requireText(JsonNode packJson, String field, Path packFile) {
        JsonNode value = packJson.get(field);
        if (value == null || !value.isTextual() || value.asText("").isBlank()) {
            throw new IllegalArgumentException(
                    "pack file " + packFile + " must declare a non-blank string '" + field + "'");
        }
        return value.asText();
    }
}
