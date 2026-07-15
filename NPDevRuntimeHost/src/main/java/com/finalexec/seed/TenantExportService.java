package com.finalexec.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * LNCH-9: the export counterpart to {@link SeedDataService} -- dumps every record of every
 * concept for the caller's tenant into the SAME {@code kind: "raw", records: [{concept, id,
 * data}]} shape {@link SeedDataService#run} already consumes. This is deliberately not a new
 * format: an operator's escape hatch (export before walking away from the platform) and a
 * poor-man's tenant clone/migration tool (export from tenant A, run as a raw seed against tenant
 * B) fall out of reusing the seeder's own shape instead of inventing a second one.
 *
 * <p>Concept names come from the classpath {@code npdev/metadata/concepts.manifest.json} (the
 * same generator-emitted manifest the editor/tooling reads) rather than the private per-request
 * concept-binding map the generated CRUD controller builds for itself -- that map isn't exposed
 * as a reusable bean, and the manifest is already the platform's own source of truth for "what
 * concepts does this app have".</p>
 */
@Service
public class TenantExportService {

    private static final String MANIFEST_PATH = "classpath:npdev/metadata/concepts.manifest.json";

    private final ResourceLoader resourceLoader;
    private final ConceptGateway conceptGateway;
    private final ObjectMapper objectMapper;

    public TenantExportService(ResourceLoader resourceLoader, ConceptGateway conceptGateway, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.conceptGateway = conceptGateway;
        this.objectMapper = objectMapper;
    }

    public ObjectNode export(ExecutionContext context) {
        List<String> conceptNames = loadConceptNames();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "raw");
        root.put("exportedAt", Instant.now().toString());
        root.put("tenantId", context.tenantId());
        ArrayNode records = root.putArray("records");

        for (String conceptName : conceptNames) {
            List<ConceptRecord> rows = conceptGateway.list(
                    new ConceptListRequest(conceptName, context.tenantId(), null, null), context);
            for (ConceptRecord row : rows) {
                ObjectNode record = records.addObject();
                record.put("concept", row.conceptName());
                record.put("id", row.id());
                record.set("data", objectMapper.valueToTree(row.data()));
            }
        }
        return root;
    }

    private List<String> loadConceptNames() {
        Resource resource = resourceLoader.getResource(MANIFEST_PATH);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode manifest = objectMapper.readTree(in);
            JsonNode items = manifest.get("items");
            List<String> names = new ArrayList<>();
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode name = item.get("name");
                    if (name != null && !name.isNull() && !name.asText().isBlank()) {
                        names.add(name.asText());
                    }
                }
            }
            return names;
        } catch (IOException exception) {
            throw new SeedDataService.SeedLoadException(
                    "Failed to read concepts manifest: " + exception.getMessage(), exception);
        }
    }
}
