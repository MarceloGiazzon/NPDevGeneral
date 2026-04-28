package com.npdev.dsl.v1.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledMetadataCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FileSystemModelRepository implements ModelRepository {

    private final Path modelsDir;
    private final ObjectMapper objectMapper;

    public FileSystemModelRepository(Path modelsDir) {
        this(modelsDir, new ObjectMapper().findAndRegisterModules());
    }

    public FileSystemModelRepository(Path modelsDir, ObjectMapper objectMapper) {
        this.modelsDir = modelsDir.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelArtifact publish(Path modelPath) throws IOException {
        Path normalizedModelPath = modelPath.toAbsolutePath().normalize();
        if (!Files.exists(normalizedModelPath)) {
            throw new IllegalArgumentException("Model file does not exist: " + normalizedModelPath);
        }

        ModelAst ast = new JsonModelParser().parse(normalizedModelPath);

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        if (validation.hasErrors()) {
            throw new IllegalArgumentException("Model validation failed: " + String.join("; ", validation.getErrors()));
        }

        CompiledModel compiledModel = new ModelCompiler().compile(ast);

        Path tmp = Files.createTempFile("npdev-compiled-", ".json");
        try {
            CompiledModelCanonicalJson.write(tmp, compiledModel);
            byte[] canonicalBytes = Files.readAllBytes(tmp);
            String hash = sha256Hex(canonicalBytes);

            String name = inferModelName(ast, normalizedModelPath);

            Path artifactRoot = modelsDir.resolve(name).resolve(hash).toAbsolutePath().normalize();
            Files.createDirectories(artifactRoot);

            Path outModel = artifactRoot.resolve("model.json");
            Path outCompiled = artifactRoot.resolve("compiled-model.json");
            Path outCompiledMetadata = artifactRoot.resolve("compiled-metadata.json");
            Path outManifest = artifactRoot.resolve("manifest.json");

            Files.copy(normalizedModelPath, outModel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(outCompiled, canonicalBytes);
            CompiledMetadataCanonicalJson.write(outCompiledMetadata, normalizedModelPath, compiledModel);

            Map<String, String> files = new LinkedHashMap<>();
            files.put("model", outModel.getFileName().toString());
            files.put("compiledModel", outCompiled.getFileName().toString());
            files.put("compiledMetadata", outCompiledMetadata.getFileName().toString());

            ModelArtifactManifest manifest = new ModelArtifactManifest(
                    name,
                    hash,
                    Instant.now().toString(),
                    files
            );

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outManifest.toFile(), manifest);

            return new ModelArtifact(
                    name,
                    hash,
                    artifactRoot,
                    outModel,
                    outCompiled,
                    outCompiledMetadata,
                    outManifest
            );
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Override
    public List<ModelArtifact> list() throws IOException {
        List<ModelArtifact> artifacts = new ArrayList<>();
        if (!Files.isDirectory(modelsDir)) {
            return artifacts;
        }

        try (DirectoryStream<Path> modelNames = Files.newDirectoryStream(modelsDir)) {
            for (Path modelDir : modelNames) {
                if (!Files.isDirectory(modelDir)) {
                    continue;
                }
                String name = modelDir.getFileName().toString();
                try (DirectoryStream<Path> hashes = Files.newDirectoryStream(modelDir)) {
                    for (Path hashDir : hashes) {
                        if (!Files.isDirectory(hashDir)) {
                            continue;
                        }
                        String hash = hashDir.getFileName().toString();
                        Path modelJson = hashDir.resolve("model.json");
                        Path compiledJson = hashDir.resolve("compiled-model.json");
                        Path compiledMetadataJson = hashDir.resolve("compiled-metadata.json");
                        Path manifestJson = hashDir.resolve("manifest.json");
                        artifacts.add(new ModelArtifact(
                                name,
                                hash,
                                hashDir,
                                modelJson,
                                compiledJson,
                                compiledMetadataJson,
                                manifestJson
                        ));
                    }
                }
            }
        }
        return artifacts;
    }

    @Override
    public Optional<ModelArtifactManifest> readManifest(String name, String hash) throws IOException {
        Path manifestPath = modelsDir.resolve(name).resolve(hash).resolve("manifest.json").toAbsolutePath().normalize();
        if (!Files.exists(manifestPath)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(manifestPath.toFile(), ModelArtifactManifest.class));
    }

    private static String inferModelName(ModelAst ast, Path modelPath) {
        String namespace = ast.getNamespace();
        if (namespace != null && !namespace.isBlank()) {
            return namespace.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        }
        String fileName = modelPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute sha256", exception);
        }
    }
}
