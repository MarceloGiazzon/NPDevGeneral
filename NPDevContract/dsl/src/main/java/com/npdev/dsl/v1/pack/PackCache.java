package com.npdev.dsl.v1.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

/**
 * PK-5 steps 1+4 ("Distribution", {@code PACK-ROADMAP.md} card PK-5): the content-addressed local
 * pack cache at {@code ~/.npdev/packs/sha256/<digest>/}. Immutable and shared across every app on
 * the machine, exactly as the card specifies -- entries are keyed purely by the SHA-256 of the
 * cached pack's own {@code pack.json} bytes (the same single-file digest {@link PackLockFile#sha256}
 * already computes for LOCAL packs' {@code npdev.lock} entries; this reuses it rather than inventing
 * a second digest scheme -- see PACK-1's own card text: "packDigest is computable today, and PK-5
 * makes it load-bearing").
 *
 * <p><b>Digest verification on every read, not just on fetch</b> (step 4): {@link #read(String)}
 * always recomputes the entry's live digest and compares it against the directory name that IS the
 * expected digest -- there is no separate "trust this once" step. A cache entry whose bytes were
 * corrupted or tampered with after being written is a hard refusal ({@link IOException}), never a
 * silent stale read.
 *
 * <p>A pack can have local fragments ({@code $ref} files alongside its own {@code pack.json}, see
 * {@code ModelSourceResolver#resolvePackRoot}) -- {@link #store} copies the WHOLE fetched tree into
 * the cache entry so those fragments are still resolvable once cached, even though (matching the
 * existing local-pack precedent in {@code PackDependencyGraphWalker#toLockEntries}) only {@code
 * pack.json} itself is hashed. Fragment-tree hashing is a pre-existing scope limitation shared by
 * local and remote packs alike, not something this feature introduces.
 */
public final class PackCache {

    public static final String ENV_ROOT_OVERRIDE = "NPDEV_PACK_CACHE_ROOT";
    /** Same override, as a JVM system property -- unlike an environment variable, this CAN be set
     *  from inside an already-running test JVM ({@code System.setProperty}, no reflection, no
     *  subprocess), which is exactly what lets integration tests exercising the real (default-root)
     *  code path -- {@code PackDependencyGraphWalker.resolveRemotePackFile}'s {@code
     *  PackCache.atDefaultRoot()} call -- stay hermetic instead of writing into the real machine's
     *  {@code ~/.npdev/packs}. Checked first; the env var remains the real, documented override for
     *  actual CLI use. */
    public static final String PROPERTY_ROOT_OVERRIDE = "npdev.pack.cache.root";
    private static final String ENTRY_FILE_NAME = "pack.json";

    private final Path root;

    public PackCache(Path root) {
        this.root = root;
    }

    /** {@code ~/.npdev/packs}, or {@code $NPDEV_PACK_CACHE_ROOT} when set (tests only -- production
     *  code always uses the real home-directory default; no production call site reads the env
     *  override itself, only this method does, so there is exactly one place that can redirect the
     *  cache). */
    public static Path defaultRoot() {
        String propertyOverride = System.getProperty(PROPERTY_ROOT_OVERRIDE);
        if (propertyOverride != null && !propertyOverride.isBlank()) {
            return Path.of(propertyOverride);
        }
        String envOverride = System.getenv(ENV_ROOT_OVERRIDE);
        if (envOverride != null && !envOverride.isBlank()) {
            return Path.of(envOverride);
        }
        return Path.of(System.getProperty("user.home"), ".npdev", "packs");
    }

    public static PackCache atDefaultRoot() {
        return new PackCache(defaultRoot());
    }

    public Path root() {
        return root;
    }

    public Path entryDir(String digestHex) {
        return root.resolve("sha256").resolve(digestHex);
    }

    public boolean has(String digestHex) {
        return Files.isDirectory(entryDir(digestHex));
    }

    /**
     * Digest-verified read: returns the path to the entry's {@code pack.json}, having just
     * recomputed its SHA-256 and confirmed it equals {@code digestHex} (the directory name itself).
     *
     * @throws IOException if the entry does not exist, is missing its {@code pack.json}, or its
     *                      live content digest does not match {@code digestHex} -- corruption or
     *                      tampering, always a hard refusal.
     */
    public Path read(String digestHex) throws IOException {
        Path dir = entryDir(digestHex);
        Path packJson = dir.resolve(ENTRY_FILE_NAME);
        if (!Files.isRegularFile(packJson)) {
            throw new IOException("pack cache entry missing or incomplete: " + dir
                    + " (expected " + ENTRY_FILE_NAME + ") -- run 'npdev pack add' to (re)populate it");
        }
        String expected = "sha256:" + digestHex;
        String live = PackLockFile.sha256(packJson);
        if (!live.equals(expected)) {
            throw new IOException("pack cache entry CORRUPT: " + packJson + " -- expected digest " + expected
                    + ", computed " + live + " -- refusing to use a tampered or corrupted cache entry; delete "
                    + dir + " and run 'npdev pack add' to refetch");
        }
        return packJson;
    }

    /**
     * Copies {@code fetchedTreeRoot} (which must directly contain a {@code pack.json}) into the
     * cache under the SHA-256 of that {@code pack.json}'s own bytes, and returns the resulting
     * digest (hex, no {@code sha256:} prefix -- the same string {@link #entryDir} and {@link
     * #read} key on).
     *
     * <p>Write-once: if an entry already exists at the computed digest, it is verified (never
     * blindly trusted) and returned as-is -- by construction, re-storing IDENTICAL content is a
     * no-op, since the digest IS the content hash; a same-digest entry can never legitimately
     * differ. The write itself stages into a sibling temp directory first and moves it into place
     * with {@link StandardCopyOption#ATOMIC_MOVE} (same filesystem, since the temp dir is created
     * under this cache's own root), so a reader can never observe a partially-written entry.
     */
    public String store(Path fetchedTreeRoot) throws IOException {
        Path sourcePackJson = fetchedTreeRoot.resolve(ENTRY_FILE_NAME);
        if (!Files.isRegularFile(sourcePackJson)) {
            throw new IOException("fetched pack tree has no " + ENTRY_FILE_NAME + " at its root: " + fetchedTreeRoot);
        }
        String digest = PackLockFile.sha256(sourcePackJson);
        String hex = digest.substring("sha256:".length());
        Path dest = entryDir(hex);
        if (Files.isDirectory(dest)) {
            read(hex); // corrupt pre-existing entry at this digest must still refuse, never silently "win"
            return hex;
        }

        Path stagingParent = root.resolve("sha256");
        Files.createDirectories(stagingParent);
        Path staging = Files.createTempDirectory(stagingParent, "staging-");
        boolean committed = false;
        try {
            copyTree(fetchedTreeRoot, staging);
            Files.createDirectories(dest.getParent());
            try {
                Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raceOrCrossDevice) {
                // Another process/thread may have populated the same digest between our existence
                // check and this move (harmless -- same digest means same content by construction),
                // or ATOMIC_MOVE genuinely isn't supported here; either way, if the destination is
                // now present and verifies, treat it as success rather than failing a correct store.
                if (!Files.isDirectory(dest)) {
                    throw raceOrCrossDevice;
                }
            }
            committed = true;
        } finally {
            if (!committed) {
                deleteTree(staging);
            }
        }
        read(hex); // re-verify what actually landed on disk before declaring success
        return hex;
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE; // VCS metadata never belongs in the cache
                }
                Path target = dest.resolve(source.relativize(dir));
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file));
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp staging dir -- never fails the caller's real operation
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
