package com.finalexec.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-9 DoD: "tenant export JSON round-trips through the seeder." Proves it end to end: seed
 * tenant A via {@link SeedDataService} (raw kind), export tenant A via
 * {@link TenantExportService}, feed that exact export back into {@link SeedDataService#run} as a
 * seed targeting tenant B, and confirm tenant B ends up with equivalent data.
 */
class TenantExportRoundTripTest {

    @Test
    void exportedJsonRoundTripsThroughTheSeederIntoADifferentTenant() {
        InMemoryConceptGateway gateway = new InMemoryConceptGateway();
        ObjectMapper objectMapper = new ObjectMapper();

        String sourceSeedJson = """
                {"kind":"raw","records":[
                  {"concept":"ContactMessage","id":"m-1","data":{"name":"Ada","email":"ada@example.com","status":"NEW"}},
                  {"concept":"ContactMessage","id":"m-2","data":{"name":"Grace","email":"grace@example.com","status":"NEW"}}
                ]}""";
        String conceptsManifestJson = """
                {"items":[{"name":"ContactMessage"}]}""";

        ExecutionContext tenantA = ExecutionContext.of("tenant-a", "op");
        ExecutionContext tenantB = ExecutionContext.of("tenant-b", "op");

        // 1. Seed tenant A through the normal seeder path.
        FakeResourceLoader seedLoader = new FakeResourceLoader(Map.of(
                "classpath:npdev-seed/data-seeds/source.json", sourceSeedJson
        ));
        SeedDataService seedDataService = new SeedDataService(seedLoader, gateway, objectMapper);
        SeedDataService.SeedRunResult seedResult = seedDataService.run("source", tenantA);
        assertTrue(seedResult.ok(), "Seeding tenant A must succeed: " + seedResult.failureMessage());
        assertEquals(2, gateway.list(new ConceptListRequest("ContactMessage", "tenant-a", null, null), tenantA).size());
        assertEquals(0, gateway.list(new ConceptListRequest("ContactMessage", "tenant-b", null, null), tenantB).size());

        // 2. Export tenant A.
        FakeResourceLoader exportLoader = new FakeResourceLoader(Map.of(
                "classpath:npdev/metadata/concepts.manifest.json", conceptsManifestJson
        ));
        TenantExportService exportService = new TenantExportService(exportLoader, gateway, objectMapper);
        ObjectNode exported = exportService.export(tenantA);
        assertEquals("raw", exported.get("kind").asText());
        assertEquals(2, exported.get("records").size());

        // 3. Feed the export back into the seeder, targeting tenant B.
        FakeResourceLoader reimportLoader = new FakeResourceLoader(Map.of(
                "classpath:npdev-seed/data-seeds/reimport.json", exported.toString()
        ));
        SeedDataService reimportService = new SeedDataService(reimportLoader, gateway, objectMapper);
        SeedDataService.SeedRunResult reimportResult = reimportService.run("reimport", tenantB);
        assertTrue(reimportResult.ok(), "Re-importing the export into tenant B must succeed: " + reimportResult.failureMessage());

        // 4. Tenant B now has the same records; tenant A is untouched.
        List<ConceptRecord> tenantBRows = gateway.list(new ConceptListRequest("ContactMessage", "tenant-b", null, null), tenantB);
        assertEquals(2, tenantBRows.size());
        Set<String> tenantBEmails = new LinkedHashSet<>();
        tenantBRows.forEach(row -> tenantBEmails.add(String.valueOf(row.data().get("email"))));
        assertEquals(Set.of("ada@example.com", "grace@example.com"), tenantBEmails);
        assertEquals(2, gateway.list(new ConceptListRequest("ContactMessage", "tenant-a", null, null), tenantA).size());
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

    /** Minimal tenant-partitioned in-memory {@link ConceptGateway} for this test only. */
    private static final class InMemoryConceptGateway implements ConceptGateway {
        private final Map<String, ConceptRecord> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return Optional.ofNullable(rows.get(key(request.conceptName(), context.tenantId(), request.id())));
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            String tenantId = request.tenantId() != null ? request.tenantId() : context.tenantId();
            return rows.values().stream()
                    .filter(row -> row.conceptName().equals(request.conceptName()) && row.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            Map<String, Object> data = new LinkedHashMap<>(request.data());
            ConceptRecord record = new ConceptRecord(request.conceptName(), request.id(), context.tenantId(), data);
            rows.put(key(request.conceptName(), context.tenantId(), request.id()), record);
            return record;
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            rows.remove(key(request.conceptName(), context.tenantId(), request.id()));
        }

        @Override
        public List<ConceptGatewayTraceRecord> explain() {
            return List.of();
        }

        private static String key(String conceptName, String tenantId, String id) {
            return tenantId + "::" + conceptName + "::" + id;
        }
    }
}
