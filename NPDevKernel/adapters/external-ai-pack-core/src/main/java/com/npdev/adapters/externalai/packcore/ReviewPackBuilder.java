package com.npdev.adapters.externalai.packcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * ADR-0009 / P6: the product's in-app pack producer -- the Java twin of
 * {@code scripts/external-review/build-review-pack.py}. Given the same mission id, source
 * descriptor, and ordered content sections, this MUST compute a byte-identical
 * {@code manifestSha256} to the Python producer (the plan's own P6 conformance requirement) --
 * verified against a golden hash captured from a real run of the Python script in
 * {@code ReviewPackBuilderPythonParityTest}.
 *
 * <p><b>Known limitation:</b> canonical manifest serialization matches Python's
 * {@code json.dumps(..., sort_keys=True, separators=(",", ":"))} only for ASCII content --
 * Python's default (ensure_ascii=True) backslash-escapes non-ASCII codepoints where Jackson's
 * default writes raw UTF-8. Source-code missions are ASCII in practice; true Unicode parity would
 * need an ASCII-escaping JSON generator on this side, not built here.</p>
 */
public final class ReviewPackBuilder {

    private static final String SECRET_PATTERNS_RESOURCE = "/npdev/redaction/secret-content-patterns.json";
    private static final String REDACTION_POLICY_VERSION =
            "sensitive-key-patterns.json+secret-content-patterns.json@2026-07-26";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReviewPackBuilder() {
    }

    public record ContentSection(String label, String text) {
    }

    /** Thrown when the sanitizer finds a secret-shaped pattern; the pack is never built past this point. */
    public static final class SanitizerFailedException extends RuntimeException {
        public SanitizerFailedException(int hitCount) {
            super("Sanitizer found " + hitCount + " secret-pattern hit(s); pack not built");
        }
    }

    /**
     * @param source the pack's "source" object verbatim (e.g. {kind: "product-app", appId, modelVersion}) --
     *               copied through unchanged into both the pack and the manifest hash input.
     */
    public static Map<String, Object> build(
            String missionId,
            Map<String, String> source,
            List<ContentSection> sections,
            List<String> shown,
            List<String> notShown,
            int chunkLines
    ) {
        List<SecretPattern> secretPatterns = loadSecretPatterns();
        int hitCount = 0;
        for (ContentSection section : sections) {
            for (SecretPattern pattern : secretPatterns) {
                if (pattern.compiled().matcher(section.text()).find()) {
                    hitCount++;
                }
            }
        }
        if (hitCount > 0) {
            throw new SanitizerFailedException(hitCount);
        }

        List<Map<String, Object>> chunks = new ArrayList<>();
        for (ContentSection section : sections) {
            chunks.addAll(chunk(section.label(), section.text(), chunkLines));
        }

        String manifestSha256 = sha256(canonicalJson(canonicalManifestInput(missionId, source, chunks)));

        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("missionId", missionId);
        pack.put("generatedAt", Instant.now().toString());
        pack.put("source", source);
        pack.put("redactionPolicyVersion", REDACTION_POLICY_VERSION);
        Map<String, Object> sanitizer = new LinkedHashMap<>();
        sanitizer.put("secretHitCount", hitCount);
        sanitizer.put("patternsChecked", secretPatterns.stream().map(SecretPattern::id).toList());
        pack.put("sanitizer", sanitizer);
        pack.put("chunks", chunks);
        pack.put("manifestSha256", manifestSha256);
        int budgetLines = chunks.stream().mapToInt(c -> (int) c.get("lineCount")).sum();
        pack.put("budgetLines", budgetLines);
        pack.put("shown", shown);
        pack.put("notShown", notShown);
        return pack;
    }

    private static List<Map<String, Object>> chunk(String label, String text, int chunkLines) {
        List<String> lines = splitLinesKeepEnds(text);
        if (lines.isEmpty()) {
            return List.of(chunkEntry(label, 0, 1, ""));
        }
        List<Integer> starts = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += chunkLines) {
            starts.add(start);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = Math.min(start + chunkLines, lines.size());
            StringBuilder chunkText = new StringBuilder();
            for (int i = start; i < end; i++) {
                chunkText.append(lines.get(i));
            }
            out.add(chunkEntry(label, index, starts.size(), chunkText.toString()));
        }
        return out;
    }

    private static Map<String, Object> chunkEntry(String path, int index, int chunkCount, String text) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("chunkId", path + "#chunk" + index);
        entry.put("sha256", sha256(text));
        entry.put("lineCount", splitLinesKeepEnds(text).size());
        entry.put("label", chunkCount > 1 ? path + " (chunk " + (index + 1) + "/" + chunkCount + ")" : path);
        entry.put("text", text);
        return entry;
    }

    /** Mirrors Python's {@code str.splitlines(keepends=True)} for LF-terminated text. */
    private static List<String> splitLinesKeepEnds(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static Map<String, Object> canonicalManifestInput(
            String missionId, Map<String, String> source, List<Map<String, Object>> chunks) {
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("missionId", missionId);
        manifest.put("source", new TreeMap<>(source));
        manifest.put("redactionPolicyVersion", REDACTION_POLICY_VERSION);
        List<Map<String, Object>> manifestChunks = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("chunkId", chunk.get("chunkId"));
            entry.put("sha256", chunk.get("sha256"));
            entry.put("lineCount", chunk.get("lineCount"));
            entry.put("label", chunk.get("label"));
            manifestChunks.add(entry);
        }
        manifest.put("chunks", manifestChunks);
        return manifest;
    }

    private static String canonicalJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed serializing canonical manifest", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record SecretPattern(String id, Pattern compiled) {
    }

    private static List<SecretPattern> loadSecretPatterns() {
        try (InputStream in = ReviewPackBuilder.class.getResourceAsStream(SECRET_PATTERNS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + SECRET_PATTERNS_RESOURCE);
            }
            JsonNode root = MAPPER.readTree(in);
            List<SecretPattern> patterns = new ArrayList<>();
            for (JsonNode node : root.path("patterns")) {
                patterns.add(new SecretPattern(node.path("id").asText(), Pattern.compile(node.path("regex").asText())));
            }
            return patterns;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed loading " + SECRET_PATTERNS_RESOURCE, e);
        }
    }
}
