package com.npdev.generator.emitters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Path A P5.3 (NPDEV_PATH_A_REALIGNMENT_PLAN.md): the ONE SHA-256 formula both sides of a
 * regeneration-conflict comparison must agree on byte-for-byte -- {@link ExtensionInventoryEmitter}
 * computes it once per generation (over the files it just wrote, under the generation output root)
 * and stamps it as {@code contentHash} on {@code untrustedExtensionAsset}/{@code javaHook} entries;
 * {@code com.npdev.generator.assembly.FinalAppAssembler} recomputes it, next regeneration, over
 * whatever is still on disk in the FinalApp (before wiping anything) to tell a clean file from a
 * hand-edited one. A shared helper rather than two independent implementations, because a silent
 * formula drift between them would make every file look "changed" (or worse, every changed file look
 * "clean") with no error anywhere -- exactly the class of twin-pair defect this repo already tracks
 * mechanically for other paired mechanisms.
 */
public final class GeneratedContentHash {

    private GeneratedContentHash() {
    }

    /**
     * @param root          the root the relative paths resolve against.
     * @param relativePaths the files to hash, order-independent (sorted internally).
     * @return a hex SHA-256 digest, or {@code null} (never a hash of nothing/partial content) when
     *         there are no paths, {@code root} is null, or any listed path is not actually a regular
     *         file on disk right now -- a caller comparing hashes across two points in time must
     *         treat a {@code null} result as "cannot verify", never as "unchanged".
     */
    public static String of(Path root, List<String> relativePaths) {
        if (root == null || relativePaths == null || relativePaths.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> sorted = new ArrayList<>(relativePaths);
            sorted.sort(Comparator.naturalOrder());
            for (String relativePath : sorted) {
                Path file = root.resolve(relativePath).normalize();
                if (!Files.isRegularFile(file)) {
                    return null;
                }
                byte[] bytes = Files.readAllBytes(file);
                // Length-prefixed so two different path/content splits can never collide on the same
                // digest (a plain concatenation could).
                digest.update(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
