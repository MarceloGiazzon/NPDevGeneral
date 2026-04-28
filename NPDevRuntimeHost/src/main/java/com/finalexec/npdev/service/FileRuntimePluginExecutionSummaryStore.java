package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public final class FileRuntimePluginExecutionSummaryStore implements RuntimePluginExecutionSummaryStore {

    private static final Logger LOG = Logger.getLogger(FileRuntimePluginExecutionSummaryStore.class.getName());

    private final ObjectMapper objectMapper;
    private final Path storePath;

    public FileRuntimePluginExecutionSummaryStore(ObjectMapper objectMapper, Path storePath) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.storePath = Objects.requireNonNull(storePath, "storePath").toAbsolutePath().normalize();
    }

    @Override
    public synchronized void append(SandboxedPluginExecutionResult.Summary summary) {
        Objects.requireNonNull(summary, "summary");
        try {
            ensureParentDirectory();
            Files.writeString(
                    storePath,
                    objectMapper.writeValueAsString(summary) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed persisting plugin execution summary to " + storePath, exception);
        }
    }

    @Override
    public synchronized List<SandboxedPluginExecutionResult.Summary> recent(int limit) {
        int effectiveLimit = Math.max(limit, 1);
        if (!Files.exists(storePath)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(storePath, StandardCharsets.UTF_8);
            List<SandboxedPluginExecutionResult.Summary> summaries = new ArrayList<>();
            for (int index = lines.size() - 1; index >= 0 && summaries.size() < effectiveLimit; index--) {
                String line = lines.get(index);
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    summaries.add(objectMapper.readValue(line, SandboxedPluginExecutionResult.Summary.class));
                } catch (IOException exception) {
                    LOG.warning("Skipping unreadable plugin execution summary from " + storePath + ": " + exception.getMessage());
                }
            }
            return List.copyOf(summaries);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed reading plugin execution summaries from " + storePath, exception);
        }
    }

    @Override
    public synchronized Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("storageKind", "file");
        diagnostics.put("storePath", storePath.toString());
        diagnostics.put("entryCount", countEntries());
        return Map.copyOf(diagnostics);
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = storePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private long countEntries() {
        if (!Files.exists(storePath)) {
            return 0L;
        }
        try {
            return Files.lines(storePath, StandardCharsets.UTF_8)
                    .filter(line -> line != null && !line.isBlank())
                    .count();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed counting plugin execution summaries in " + storePath, exception);
        }
    }
}
