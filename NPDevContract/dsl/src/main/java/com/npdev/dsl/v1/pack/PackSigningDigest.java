package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The digest a pack signature signs (REG-359, pack:signature).
 *
 * <p>The signature lives INSIDE {@code pack.json}, so the digest it signs cannot be a hash of the bytes of the
 * tree it sits in: adding the signature changes those bytes, and {@code PackCache.sha256OfTree} /
 * {@code PackLockFile.sha256} (kept as they are: they are storage identities, signature included) could never equal
 * a digest taken before the signature existed. That circularity made an embedded signature impossible to verify.
 *
 * <p>This digest is the same shape as {@code PackCache.sha256OfTree} (every regular file under the tree, sorted by
 * normalised path, {@code path NUL bytes NUL}, {@code .git} skipped) with ONE difference: the root
 * {@code pack.json} contributes the bytes of its CANONICAL JSON with the top-level {@code signature} member removed
 * (object keys sorted, compact, UTF-8), and every other {@code *.json} file of the pack (a fragment) contributes its
 * canonical JSON too. Two things follow: the signature does not change what it signs, and re-formatting or
 * re-ending a JSON file (an editor, a pretty printer, the publisher that injects the signature, a Windows checkout
 * that turns LF into CRLF) does not invalidate it, while any change of a value or of any other file in the pack does.
 * Files that are not JSON are hashed as they are.
 */
public final class PackSigningDigest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String ENTRY_FILE = "pack.json";
    private static final String SIGNATURE_MEMBER = "signature";

    private PackSigningDigest() {
    }

    /** {@code sha256:<hex>} over the pack directory {@code root} (the directory that holds {@code pack.json}). */
    public static String of(Path root) throws IOException {
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
                String relative = normalizedRelative(root, file);
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                byte[] bytes = Files.readAllBytes(file);
                if (ENTRY_FILE.equals(relative)) {
                    bytes = canonical(bytes, true);
                } else if (relative.endsWith(".json")) {
                    bytes = canonical(bytes, false);
                }
                digest.update(bytes);
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new UncheckedIOException(new IOException(impossible));
        }
    }

    /** The canonical bytes of a JSON file, with the root {@code signature} member removed when asked; unparseable input stays raw. */
    static byte[] canonical(byte[] json, boolean withoutSignature) {
        try {
            JsonNode tree = MAPPER.readTree(json);
            if (withoutSignature && tree instanceof ObjectNode object) {
                object.remove(SIGNATURE_MEMBER);
            }
            return MAPPER.writeValueAsBytes(canonical(tree));
        } catch (IOException notJson) {
            return json;
        }
    }

    private static JsonNode canonical(JsonNode node) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = factory.objectNode();
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                sorted.set(name, canonical(object.get(name)));
            }
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = factory.arrayNode();
            for (JsonNode element : array) {
                copy.add(canonical(element));
            }
            return copy;
        }
        return node;
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static boolean isUnderGitDirectory(Path root, Path file) {
        for (Path dir = file.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
            if (".git".equals(String.valueOf(dir.getFileName()))) {
                return true;
            }
        }
        return false;
    }
}
