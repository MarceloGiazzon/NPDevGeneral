package com.finalexec.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-189: an app with exactly one {@code definition/seeds/*.json} file made
 * Build-NpdevApp.ps1 emit {@code data-seeds/index.json} as a bare JSON object rather than a
 * one-element array (PowerShell's {@code ConvertTo-Json} unrolls a single-element array through
 * the pipeline). {@link SeedDataService#listAvailable()} used to guard with
 * {@code manifest.isArray()} and silently return {@code List.of()} for anything else -- an empty
 * list indistinguishable from "this app declares no seeds", which hid a real seed from
 * {@code GET /api/admin/seeds} and the generated UI's "Load sample data" action.
 *
 * <p>The writer is now fixed (Build-NpdevApp.ps1 forces array serialization at that call site
 * via {@code Write-JsonFile -AsArray}), but this test locks in the reader-side half of the fix:
 * {@code listAvailable()} must accept a lone object as a one-element manifest, so an app already
 * built before the writer fix recovers without regeneration. It also proves the two-or-more-seed
 * case (which happened to serialize correctly, and is exactly why REG-189 survived) keeps working,
 * and that a genuinely malformed manifest (neither array nor object) fails loudly instead of
 * being swallowed into an empty list.</p>
 */
class SeedDataServiceListAvailableTest {

    private static final String MANIFEST_PATH = "classpath:npdev-seed/data-seeds/index.json";

    @Test
    void bareObjectManifestIsTreatedAsAOneElementList() {
        SeedDataService service = serviceFor(
                """
                {"id":"demo-users","label":"Demo users","description":"A handful of sample users.","kind":"smart"}""");

        List<Map<String, Object>> entries = service.listAvailable();

        assertEquals(1, entries.size());
        assertEquals("demo-users", entries.get(0).get("id"));
        assertEquals("Demo users", entries.get(0).get("label"));
        assertEquals("A handful of sample users.", entries.get(0).get("description"));
        assertEquals("smart", entries.get(0).get("kind"));
    }

    @Test
    void realArrayManifestWithMultipleEntriesStillWorks() {
        SeedDataService service = serviceFor(
                """
                [
                  {"id":"demo-users","label":"Demo users","kind":"smart"},
                  {"id":"extra-user","label":"Extra user","kind":"raw"}
                ]""");

        List<Map<String, Object>> entries = service.listAvailable();

        assertEquals(2, entries.size());
        assertEquals("demo-users", entries.get(0).get("id"));
        assertEquals("extra-user", entries.get(1).get("id"));
    }

    @Test
    void missingManifestIsAnEmptyListNotAnError() {
        SeedDataService service = new SeedDataService(new FakeResourceLoader(Map.of()), null, new ObjectMapper());

        assertTrue(service.listAvailable().isEmpty());
    }

    @Test
    void manifestThatIsNeitherArrayNorObjectFailsLoudly() {
        SeedDataService service = serviceFor("\"not-a-manifest\"");

        assertThrows(SeedDataService.SeedLoadException.class, service::listAvailable);
    }

    @Test
    void kindDefaultsToSmartWhenAbsentOnABareObjectManifest() {
        SeedDataService service = serviceFor("""
                {"id":"demo-users","label":"Demo users"}""");

        List<Map<String, Object>> entries = service.listAvailable();

        assertEquals(1, entries.size());
        assertEquals("smart", entries.get(0).get("kind"));
    }

    private static SeedDataService serviceFor(String manifestJson) {
        FakeResourceLoader loader = new FakeResourceLoader(Map.of(MANIFEST_PATH, manifestJson));
        return new SeedDataService(loader, null, new ObjectMapper());
    }

    private static final class FakeResourceLoader implements ResourceLoader {
        private final Map<String, String> resources;

        private FakeResourceLoader(Map<String, String> resources) {
            this.resources = resources;
        }

        @Override
        public Resource getResource(String location) {
            String content = resources.get(location);
            if (content == null) {
                return new ByteArrayResource(new byte[0]) {
                    @Override
                    public boolean exists() {
                        return false;
                    }
                };
            }
            return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public boolean exists() {
                    return true;
                }
            };
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}
