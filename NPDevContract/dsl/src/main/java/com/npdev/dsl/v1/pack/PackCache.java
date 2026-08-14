package com.npdev.dsl.v1.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/**
 * PK-5 steps 1+4 ("Distribution", {@code PACK-ROADMAP.md} card PK-5): the content-addressed local
 * pack cache at {@code ~/.npdev/packs/sha256/<digest>/}. Immutable and shared across every app on
 * the machine, exactly as the card specifies.
 *
 * <p><b>Digest verification on every read, not just on fetch</b> (step 4): {@link #read(String)}
 * always recomputes the entry's live digest and compares it against the directory name that IS the
 * expected digest -- there is no separate "trust this once" step. A cache entry whose bytes were
 * corrupted or tampered with after being written is a hard refusal ({@link IOException}), never a
 * silent stale read.
 *
 * <p><b>The digest covers the WHOLE cached tree, not just {@code pack.json}</b> (post-review fix,
 * adversarial review of PR #70 finding #2): a pack can have local fragments ({@code $ref} files
 * alongside its own {@code pack.json}, see {@code ModelSourceResolver#resolvePackRoot}), and {@link
 * #store} copies the WHOLE fetched tree into the cache entry so those fragments stay resolvable.
 * Hashing only {@code pack.json} (the first version of this class did, deliberately mirroring
 * {@code PackLockFile#sha256}'s existing single-file precedent for LOCAL packs' {@code npdev.lock}
 * digests) let two trees with byte-identical {@code pack.json} but DIFFERENT fragment content
 * silently alias to the same cache digest -- the write-once early-return in {@link #store} only
 * ever checked whether {@code entryDir(hex)} already existed, so the second tree's fragments would
 * never even be looked at, and {@link #read} would report the entry as digest-verified while
 * permanently serving the first tree's fragments. {@link #sha256OfTree} fixes this by hashing every
 * regular file under the tree (path + content, in a stable path-sorted order, skipping {@code .git}
 * the same way {@link #copyTree} already excludes it from what gets cached) -- a same-digest entry
 * genuinely can never differ now, for the whole tree, not just its entry file. This is intentionally
 * a SEPARATE digest scheme from {@link PackLockFile#sha256}, which stays single-file (unchanged) for
 * local packs' existing {@code npdev.lock} entries -- this class's own entries are the only thing
 * that needed to change.
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

    private static final java.util.regex.Pattern DIGEST_HEX_PATTERN = java.util.regex.Pattern.compile("^[a-f0-9]{64}$");

    /**
     * @throws IOException if {@code digestHex} isn't a well-formed SHA-256 hex string. Defense-in-
     *         depth (post-review hardening, PR #70 review finding): a real digest can never contain
     *         a path separator or {@code ..}, so this was never actually reachable as a path-
     *         traversal vector via {@link #read}'s own equality check -- but a caller that passes an
     *         unvalidated string straight from a file (e.g. an {@code npdev.lock} entry's {@code
     *         digest} field) gets a clear, named, checked-exception failure instead of a confusing
     *         downstream one if that string is ever malformed. {@link IOException}, not {@link
     *         IllegalArgumentException}, to match every other failure mode {@link #read}/{@link
     *         #store} already report -- so a caller's existing {@code catch (IOException)} still
     *         catches this one too, rather than it slipping through as an unhandled runtime type.
     */
    public Path entryDir(String digestHex) throws IOException {
        if (digestHex == null || !DIGEST_HEX_PATTERN.matcher(digestHex).matches()) {
            throw new IOException("not a well-formed sha256 hex digest: " + digestHex);
        }
        return root.resolve("sha256").resolve(digestHex);
    }

    public boolean has(String digestHex) throws IOException {
        return Files.isDirectory(entryDir(digestHex));
    }

    /**
     * Digest-verified read: returns the path to the entry's {@code pack.json}, having just
     * recomputed the WHOLE tree's SHA-256 (see this class's own doc) and confirmed it equals
     * {@code digestHex} (the directory name itself).
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
        String live = sha256OfTree(dir);
        if (!live.equals(expected)) {
            throw new IOException("pack cache entry CORRUPT: " + dir + " -- expected digest " + expected
                    + ", computed " + live + " -- refusing to use a tampered or corrupted cache entry; delete "
                    + dir + " and run 'npdev pack add' to refetch");
        }
        return packJson;
    }

    /**
     * Copies {@code fetchedTreeRoot} (which must directly contain a {@code pack.json}) into the
     * cache under the SHA-256 of the WHOLE tree's content (see this class's own doc), and returns
     * the resulting digest (hex, no {@code sha256:} prefix -- the same string {@link #entryDir} and
     * {@link #read} key on).
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
        String digest = sha256OfTree(fetchedTreeRoot);
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
                // Only a successful move actually CONSUMES the staging directory -- committed must
                // stay false in the race-loss branch below, or the loser's full tree copy leaks
                // under <root>/sha256/staging-* forever (post-review fix, PR #70 review finding,
                // "not blocking" but cheap and fixed alongside the digest change in this same
                // method).
                committed = true;
            } catch (IOException raceOrCrossDevice) {
                // Another process/thread may have populated the same digest between our existence
                // check and this move (harmless -- same digest means same content by construction),
                // or ATOMIC_MOVE genuinely isn't supported here; either way, if the destination is
                // now present and verifies, treat it as success rather than failing a correct store
                // -- but staging was never consumed, so it still needs cleanup (the `finally` below
                // handles that since committed is left false here).
                if (!Files.isDirectory(dest)) {
                    throw raceOrCrossDevice;
                }
            }
        } finally {
            if (!committed) {
                deleteTree(staging);
            }
        }
        read(hex); // re-verify what actually landed on disk before declaring success
        return hex;
    }

    /**
     * SHA-256 over every regular file under {@code root} (path-sorted for stability, {@code .git}
     * subtrees skipped the same way {@link #copyTree} excludes them from what gets cached), each
     * contributing its cache-root-relative path (forward-slash-normalized, so the digest is
     * identical on Windows and Linux) and its raw bytes, both length-delimited by a NUL separator
     * so no path/content byte sequence can be crafted to collide across a path/content boundary.
     * Called identically over the pre-copy source tree (in {@link #store}) and the post-copy cache
     * entry (in {@link #read}) -- both walks apply the same {@code .git} exclusion, so the two
     * digests agree for the same logical content.
     */
    private static String sha256OfTree(Path root) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> !isUnderGitDirectory(root, file))
                    .sorted(Comparator.comparing(file -> normalizedRelative(root, file)))
                    .toList();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                digest.update(normalizedRelative(root, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) -- never actually thrown.
            throw new UncheckedIOException(new IOException(impossible));
        }
    }

    private static boolean isUnderGitDirectory(Path root, Path file) {
        for (Path dir = file.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
            if (".git".equals(String.valueOf(dir.getFileName()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
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
