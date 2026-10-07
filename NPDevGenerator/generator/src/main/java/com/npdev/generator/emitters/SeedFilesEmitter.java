package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSeed;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/**
 * Copies every file a seed names with {@code "$file:<path>"} (relative to the model.json's own
 * directory) into the app's classpath under {@code npdev-seed/files/<path>}, where
 * {@code SeedDataService} stores it through the app's file store at seed time (G6, Pigmentampas
 * 2026-10-07: a fresh demo starts with the cap images, not empty image fields).
 *
 * <p>A named file that is missing, absolute, or escapes the model directory is a generation error,
 * never a silently empty field.
 */
public final class SeedFilesEmitter {

    static final String FILE_PREFIX = "$file:";
    static final String RESOURCE_DIR = "src/main/resources/npdev-seed/files/";

    private final GeneratedSourceWriter writer;

    public SeedFilesEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(CompiledModel model, Path modelSourcePath) {
        TreeSet<String> paths = new TreeSet<>();
        for (CompiledSeed seed : model.getSeeds()) {
            for (Object value : seed.data().values()) {
                if (value instanceof String text && text.startsWith(FILE_PREFIX)) {
                    paths.add(text.substring(FILE_PREFIX.length()).trim().replace('\\', '/'));
                }
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        if (modelSourcePath == null) {
            throw new IllegalStateException("Seeds name files (" + paths + ") but the model's source path is unknown");
        }
        Path modelDir = modelSourcePath.toAbsolutePath().normalize().getParent();
        for (String path : paths) {
            Path source = modelDir.resolve(path).normalize();
            if (path.isEmpty() || Path.of(path).isAbsolute() || !source.startsWith(modelDir)) {
                throw new IllegalStateException("Seed \"" + FILE_PREFIX + path
                        + "\" must be a relative path inside the model directory " + modelDir);
            }
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("Seed \"" + FILE_PREFIX + path + "\" names a file that does not exist: " + source);
            }
            try {
                writer.writeRelativeBytes(RESOURCE_DIR + path, Files.readAllBytes(source));
            } catch (IOException e) {
                throw new IllegalStateException("Seed \"" + FILE_PREFIX + path + "\" could not be read: " + source, e);
            }
        }
    }
}
