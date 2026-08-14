package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResolvedModelSource {
    private final Path rootModelPath;
    private final Path canonicalRootDirectory;
    private final JsonNode resolvedRoot;
    private final List<Path> includedFiles;
    private final Map<String, Path> provenanceByJsonPointer;
    private final List<ValidationDiagnostic> diagnostics;
    private final List<ValidationDiagnostic> warnings;
    private final Map<String, String> physicalQualifierByConceptName;
    private final Map<String, PackLockFile.LockedPack> migrationTrackedPacks;
    private final Map<String, Map<String, ModelSourceResolver.PackOrigin>> originByQualifiedMemberName;

    public ResolvedModelSource(
            Path rootModelPath,
            Path canonicalRootDirectory,
            JsonNode resolvedRoot,
            List<Path> includedFiles,
            Map<String, Path> provenanceByJsonPointer,
            List<ValidationDiagnostic> diagnostics,
            List<ValidationDiagnostic> warnings,
            Map<String, String> physicalQualifierByConceptName,
            Map<String, PackLockFile.LockedPack> migrationTrackedPacks,
            Map<String, Map<String, ModelSourceResolver.PackOrigin>> originByQualifiedMemberName
    ) {
        this.rootModelPath = Objects.requireNonNull(rootModelPath, "rootModelPath");
        this.canonicalRootDirectory = Objects.requireNonNull(canonicalRootDirectory, "canonicalRootDirectory");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.includedFiles = List.copyOf(includedFiles == null ? List.of() : includedFiles);
        this.provenanceByJsonPointer = Map.copyOf(provenanceByJsonPointer == null ? Map.of() : provenanceByJsonPointer);
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        this.warnings = List.copyOf(warnings == null ? List.of() : warnings);
        this.physicalQualifierByConceptName = Map.copyOf(
                physicalQualifierByConceptName == null ? Map.of() : physicalQualifierByConceptName);
        this.migrationTrackedPacks = Map.copyOf(migrationTrackedPacks == null ? Map.of() : migrationTrackedPacks);
        this.originByQualifiedMemberName = Map.copyOf(
                originByQualifiedMemberName == null ? Map.of() : originByQualifiedMemberName);
    }

    public Path rootModelPath() {
        return rootModelPath;
    }

    public Path canonicalRootDirectory() {
        return canonicalRootDirectory;
    }

    public JsonNode resolvedRoot() {
        return resolvedRoot;
    }

    public List<Path> includedFiles() {
        return includedFiles;
    }

    public Map<String, Path> provenanceByJsonPointer() {
        return provenanceByJsonPointer;
    }

    public List<ValidationDiagnostic> diagnostics() {
        return diagnostics;
    }

    public List<ValidationDiagnostic> warnings() {
        return warnings;
    }

    public Map<String, String> physicalQualifierByConceptName() {
        return physicalQualifierByConceptName;
    }

    /** PK-4 Stage D: every packId whose pack.json declares a migration chain, with a fresh
     *  resolvedVersion/digest/sourcePath entry for THIS resolve -- empty unless at least one resolved
     *  pack actually uses the feature. {@code GeneratorMain} consumes this, after a full successful
     *  generate, to advance {@code npdev.lock}'s migratedVersion bookkeeping. */
    public Map<String, PackLockFile.LockedPack> migrationTrackedPacks() {
        return migrationTrackedPacks;
    }

    /** PACK-2: pack-attribution facts for every pack-contributed member, keyed first by {@code
     *  ModelSourceResolver.MODEL_ARRAY_KEYS} kind then by the member's already-qualified name --
     *  see {@code ModelSourceResolver#recordOrigin}. Empty for a model with no {@code packs[]}. */
    public Map<String, Map<String, ModelSourceResolver.PackOrigin>> originByQualifiedMemberName() {
        return originByQualifiedMemberName;
    }

    public String resolvedModelJson() {
        return resolvedRoot.toPrettyString() + System.lineSeparator();
    }

    public Path sourceFor(String jsonPointer) {
        if (jsonPointer == null) {
            return null;
        }
        return provenanceByJsonPointer.get(jsonPointer);
    }
}
