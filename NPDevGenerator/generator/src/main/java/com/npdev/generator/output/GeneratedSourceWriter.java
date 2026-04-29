package com.npdev.generator.output;

import com.npdev.generator.strategy.RegenerationPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class GeneratedSourceWriter {

    private final Path outRoot;
    private final RegenerationPolicy policy;
    private final List<String> written = new ArrayList<>();

    public GeneratedSourceWriter(Path outRoot, RegenerationPolicy policy) {
        this.outRoot = outRoot;
        this.policy = policy;
    }

    public void writeRelative(String relativePath, String content) {
        try {
            Path p = outRoot.resolve(relativePath).normalize();
            Files.createDirectories(p.getParent());

            if (Files.exists(p) && !policy.canOverwrite(p)) {
                return;
            }

            Files.writeString(
                    p,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            written.add(p.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed writing file: " + relativePath, e);
        }
    }

    public void deleteRelativeIfExists(String relativePath) {
        try {
            Path p = outRoot.resolve(relativePath).normalize();
            if (!Files.exists(p)) {
                return;
            }
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new RuntimeException("Failed deleting file: " + relativePath, e);
        }
    }

    public void flushSummary() {
        System.out.println("Written " + written.size() + " file(s).");
        for (String f : written) {
            System.out.println(" - " + f);
        }
    }
}
