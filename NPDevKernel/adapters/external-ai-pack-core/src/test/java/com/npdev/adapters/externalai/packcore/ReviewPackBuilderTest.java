package com.npdev.adapters.externalai.packcore;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPackBuilderTest {

    private static Map<String, String> testSource() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("kind", "product-app");
        source.put("appId", "test-app");
        source.put("modelVersion", "v1");
        return source;
    }

    @Test
    void secondBuildOfIdenticalInputProducesAnIdenticalManifestHash() {
        List<ReviewPackBuilder.ContentSection> sections =
                List.of(new ReviewPackBuilder.ContentSection("A.java", "public class A {}\n"));

        Map<String, Object> first = ReviewPackBuilder.build(
                "M2-SEC-ROWAUTHZ", testSource(), sections, List.of("A.java"), List.of(), 400);
        Map<String, Object> second = ReviewPackBuilder.build(
                "M2-SEC-ROWAUTHZ", testSource(), sections, List.of("A.java"), List.of(), 400);

        assertEquals(first.get("manifestSha256"), second.get("manifestSha256"));
    }

    @Test
    void sanitizerRejectsContentContainingAnAwsAccessKey() {
        List<ReviewPackBuilder.ContentSection> sections = List.of(
                new ReviewPackBuilder.ContentSection("leak.txt", "aws_key = \"AKIAABCDEFGHIJKLMNOP\"\n"));

        ReviewPackBuilder.SanitizerFailedException thrown = assertThrows(
                ReviewPackBuilder.SanitizerFailedException.class,
                () -> ReviewPackBuilder.build(
                        "M6-AUDIT-VERDICT", testSource(), sections, List.of(), List.of(), 400));
        assertTrue(thrown.getMessage().contains("1 secret-pattern hit"));
    }

    @Test
    void cleanContentProducesZeroSanitizerHits() {
        Map<String, Object> pack = ReviewPackBuilder.build(
                "M2-SEC-ROWAUTHZ", testSource(),
                List.of(new ReviewPackBuilder.ContentSection("A.java", "public class A {}\n")),
                List.of("A.java"), List.of(), 400);

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizer = (Map<String, Object>) pack.get("sanitizer");
        assertEquals(0, sanitizer.get("secretHitCount"));
    }
}
