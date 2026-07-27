package com.npdev.adapters.externalai.packcore;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-0009 / P6 conformance requirement: the Java in-app producer must compute a byte-identical
 * {@code manifestSha256} to the Python platform producer (scripts/external-review/build-review-pack.py)
 * for the same input. The three expected hashes below were captured by actually running the
 * Python producer's own {@code chunk_content}/{@code manifest_bytes} functions against these exact
 * fixtures (single chunk, multi-chunk, and empty content) -- not independently re-derived, so a
 * divergence in either implementation's chunking or canonical-JSON serialization fails this test.
 */
class ReviewPackBuilderPythonParityTest {

    private static Map<String, String> testSource() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("kind", "product-app");
        source.put("appId", "test-app");
        source.put("modelVersion", "v1");
        return source;
    }

    @Test
    void singleChunkManifestMatchesThePythonProducer() {
        Map<String, Object> pack = ReviewPackBuilder.build(
                "M2-SEC-ROWAUTHZ",
                testSource(),
                List.of(new ReviewPackBuilder.ContentSection("TestFile.java", "line one\nline two\nline three\n")),
                List.of("TestFile.java"),
                List.of(),
                400
        );

        assertEquals(
                "fdde223a8cd2d656b9ec1fe5d2c0949e90d37dd5144e8be776ed8764899f7898",
                pack.get("manifestSha256"));
    }

    @Test
    void multiChunkManifestMatchesThePythonProducer() {
        Map<String, Object> pack = ReviewPackBuilder.build(
                "M2-SEC-ROWAUTHZ",
                testSource(),
                List.of(new ReviewPackBuilder.ContentSection("Multi.java", "a\nb\nc\nd\ne\n")),
                List.of("Multi.java"),
                List.of(),
                2
        );

        assertEquals(
                "baf059a59934ddee67f45bcf91c8a9c9c04f4cf9fc2110e50845ecf6b284479f",
                pack.get("manifestSha256"));
    }

    @Test
    void emptyContentManifestMatchesThePythonProducer() {
        Map<String, Object> pack = ReviewPackBuilder.build(
                "M2-SEC-ROWAUTHZ",
                testSource(),
                List.of(new ReviewPackBuilder.ContentSection("Empty.java", "")),
                List.of("Empty.java"),
                List.of(),
                400
        );

        assertEquals(
                "42ffcc362e450c5f064bea987e4b79c84469ccf6a04f5eb8c58472517ea48f15",
                pack.get("manifestSha256"));
    }
}
