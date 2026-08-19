package com.finalexec.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Loads {@code definition/seeds/*.json} (copied at build time by Build-NpdevApp.ps1 into the
 * {@code npdev-seed/data-seeds/} classpath folder, mirroring the WorkspaceMenuSeeder convention
 * -- see {@link com.finalexec.workspace.WorkspaceMenuSeeder}) and creates the declared records
 * through {@link ConceptGateway#save}, the same governed write path
 * {@code DefaultProcedureExecutor.saveConcept()} already uses, proven across every WmsOffice
 * Procedure. Deliberately on-demand (triggered by {@link DataSeedAdminController}), not a
 * boot-time {@code ApplicationRunner} like WorkspaceMenuSeeder -- seeds are meant to be run
 * repeatedly / selectively by an operator, not auto-applied once.
 *
 * <p>Two seed kinds share one file shape ({@code records: [{alias?, concept, id?, data} |
 * {concept, repeatOver, data}]}): {@code smart} expands {@code repeatOver} blocks and resolves
 * {@code $ref:alias} placeholders against aliases declared earlier in the same file; {@code raw}
 * saves every record exactly as written and rejects (fail-fast, before any record is saved) any
 * record that uses {@code alias}/{@code repeatOver}/a {@code $ref:} value, since raw seeds don't
 * support templating.</p>
 */
@Service
public class SeedDataService {

    private static final String MANIFEST_PATH = "classpath:npdev-seed/data-seeds/index.json";
    private static final String SEED_PATH_PREFIX = "classpath:npdev-seed/data-seeds/";
    private static final String KIND_SMART = "smart";
    private static final String KIND_RAW = "raw";
    private static final String REF_PREFIX = "$ref:";

    private final ResourceLoader resourceLoader;
    private final ConceptGateway conceptGateway;
    private final ObjectMapper objectMapper;

    public SeedDataService(ResourceLoader resourceLoader, ConceptGateway conceptGateway, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.conceptGateway = conceptGateway;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listAvailable() {
        Resource resource = resourceLoader.getResource(MANIFEST_PATH);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode manifest = objectMapper.readTree(in);
            List<Map<String, Object>> entries = new ArrayList<>();
            if (manifest.isArray()) {
                for (JsonNode entry : manifest) {
                    entries.add(manifestRow(entry));
                }
            } else if (manifest.isObject()) {
                // REG-189: PowerShell's `ConvertTo-Json` unrolls a single-element array through
                // the pipeline, so a manifest built from exactly one definition/seeds/*.json file
                // is written as a bare object rather than a one-element array. Treat it as the
                // one-element manifest it represents rather than silently returning List.of() --
                // an empty list here is indistinguishable from "this app declares no seeds", which
                // is exactly the wrong-answer shape this codebase rejects (see DataSeedAdminController
                // callers such as R7.2's "Load sample data" UI action). The writer is fixed
                // (Build-NpdevApp.ps1 now forces array serialization at this call site), so this
                // branch exists to recover already-built apps without regeneration.
                entries.add(manifestRow(manifest));
            } else if (!manifest.isNull() && !manifest.isMissingNode()) {
                throw new SeedLoadException(
                        "Seed manifest at " + MANIFEST_PATH + " is neither a JSON array nor an object (found "
                                + manifest.getNodeType() + ") -- cannot list available seeds", null);
            }
            return entries;
        } catch (IOException exception) {
            throw new SeedLoadException("Failed to read seed manifest: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> manifestRow(JsonNode entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", textOrNull(entry, "id"));
        row.put("label", textOrNull(entry, "label"));
        row.put("description", textOrNull(entry, "description"));
        row.put("kind", textOrDefault(entry, "kind", KIND_SMART));
        return row;
    }

    public SeedRunResult run(String seedId, ExecutionContext context) {
        if (seedId == null || seedId.isBlank()) {
            throw new IllegalArgumentException("seedId is required");
        }
        JsonNode seedFile = loadSeedFile(seedId);
        String kind = textOrDefault(seedFile, "kind", KIND_SMART);
        if (!KIND_SMART.equals(kind) && !KIND_RAW.equals(kind)) {
            throw new IllegalArgumentException(
                    "Unsupported seed kind '" + kind + "' for seed " + seedId + " (expected 'smart' or 'raw')");
        }

        List<JsonNode> declaredRecords = new ArrayList<>();
        JsonNode recordsNode = seedFile.get("records");
        if (recordsNode != null && recordsNode.isArray()) {
            recordsNode.forEach(declaredRecords::add);
        }
        if (KIND_RAW.equals(kind)) {
            validateRawRecords(seedId, declaredRecords);
        }

        Map<String, String> aliasToId = new LinkedHashMap<>();
        Map<String, Integer> createdCounts = new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();

        for (int recordIndex = 0; recordIndex < declaredRecords.size(); recordIndex++) {
            JsonNode declared = declaredRecords.get(recordIndex);
            List<ExpandedRecord> expanded = KIND_SMART.equals(kind)
                    ? expandSmartRecord(declared)
                    : expandRawRecord(declared);

            for (ExpandedRecord record : expanded) {
                try {
                    Map<String, Object> data = KIND_SMART.equals(kind)
                            ? resolveReferences(record.data(), aliasToId)
                            : record.data();
                    String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
                    // The concept schema declares "id" as a required field, and
                    // DefaultConceptGateway's semantic policy validates required-field presence
                    // against the data map itself (not the separate ConceptWriteRequest.id param)
                    // -- confirmed live: omitting this produced "Required concept field is
                    // missing: User.id" even though id was already passed to ConceptWriteRequest.
                    Map<String, Object> dataWithId = new LinkedHashMap<>(data);
                    dataWithId.put("id", id);
                    conceptGateway.save(new ConceptWriteRequest(record.concept(), id, null, dataWithId), context);
                    createdCounts.merge(record.concept(), 1, Integer::sum);
                    if (record.alias() != null) {
                        aliasToId.put(record.alias(), id);
                    }
                } catch (RuntimeException exception) {
                    return SeedRunResult.failure(
                            seedId, kind, createdCounts,
                            recordIndex, record.alias(), record.concept(),
                            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                            System.currentTimeMillis() - startedAt
                    );
                }
            }
        }
        return SeedRunResult.success(seedId, kind, createdCounts, System.currentTimeMillis() - startedAt);
    }

    private JsonNode loadSeedFile(String seedId) {
        String safeId = seedId.replaceAll("[^a-zA-Z0-9_-]", "");
        if (!safeId.equals(seedId)) {
            throw new IllegalArgumentException("Invalid seedId: " + seedId);
        }
        Resource resource = resourceLoader.getResource(SEED_PATH_PREFIX + safeId + ".json");
        if (!resource.exists()) {
            throw new SeedNotFoundException("Seed not found: " + seedId);
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException exception) {
            throw new SeedLoadException("Failed to read seed " + seedId + ": " + exception.getMessage(), exception);
        }
    }

    private void validateRawRecords(String seedId, List<JsonNode> declaredRecords) {
        for (int i = 0; i < declaredRecords.size(); i++) {
            JsonNode record = declaredRecords.get(i);
            if (record.has("alias")) {
                throw new IllegalArgumentException(
                        "Raw seed '" + seedId + "' record[" + i + "] declares 'alias' -- raw seeds don't support templating");
            }
            if (record.has("repeatOver")) {
                throw new IllegalArgumentException(
                        "Raw seed '" + seedId + "' record[" + i + "] declares 'repeatOver' -- raw seeds don't support templating");
            }
            JsonNode data = record.get("data");
            if (data != null && data.isObject()) {
                for (var it = data.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode value = entry.getValue();
                    if (value.isTextual() && value.asText().startsWith(REF_PREFIX)) {
                        throw new IllegalArgumentException(
                                "Raw seed '" + seedId + "' record[" + i + "] field '" + entry.getKey()
                                        + "' looks like a $ref placeholder ('" + value.asText()
                                        + "') -- raw seeds don't resolve $ref, only 'smart' seeds do");
                    }
                }
            }
        }
    }

    private List<ExpandedRecord> expandRawRecord(JsonNode declared) {
        String concept = requireConcept(declared);
        String id = textOrNull(declared, "id");
        Map<String, Object> data = toMap(declared.get("data"));
        return List.of(new ExpandedRecord(null, concept, id, data));
    }

    private List<ExpandedRecord> expandSmartRecord(JsonNode declared) {
        String concept = requireConcept(declared);
        JsonNode repeatOver = declared.get("repeatOver");
        if (repeatOver == null || repeatOver.isNull()) {
            String alias = textOrNull(declared, "alias");
            Map<String, Object> data = toMap(declared.get("data"));
            return List.of(new ExpandedRecord(alias, concept, null, data));
        }
        if (declared.has("alias")) {
            throw new IllegalArgumentException(
                    "Seed record for concept '" + concept + "' declares both 'alias' and 'repeatOver' -- bulk-generated rows can't be aliased");
        }

        JsonNode varsNode = repeatOver.get("vars");
        if (varsNode == null || !varsNode.isObject() || varsNode.isEmpty()) {
            throw new IllegalArgumentException("Seed record for concept '" + concept + "' has 'repeatOver' with no 'vars'");
        }
        List<String> varNames = new ArrayList<>();
        List<int[]> varRanges = new ArrayList<>();
        for (var it = varsNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode range = entry.getValue();
            if (!range.isArray() || range.size() != 2) {
                throw new IllegalArgumentException("repeatOver.vars." + entry.getKey() + " must be a [min,max] pair");
            }
            varNames.add(entry.getKey());
            varRanges.add(new int[]{range.get(0).asInt(), range.get(1).asInt()});
        }

        JsonNode template = declared.get("data");
        if (template == null) {
            throw new IllegalArgumentException("Seed record with 'repeatOver' is missing 'data' template");
        }
        List<ExpandedRecord> results = new ArrayList<>();
        Consumer<Map<String, Integer>> onComplete = assignment ->
                results.add(new ExpandedRecord(null, concept, null, substituteVars(template, assignment)));
        expandCartesian(varNames, varRanges, 0, new LinkedHashMap<>(), onComplete);
        return results;
    }

    private void expandCartesian(
            List<String> varNames, List<int[]> varRanges, int depth,
            Map<String, Integer> assignment, Consumer<Map<String, Integer>> onComplete
    ) {
        if (depth == varNames.size()) {
            onComplete.accept(new LinkedHashMap<>(assignment));
            return;
        }
        String name = varNames.get(depth);
        int[] range = varRanges.get(depth);
        for (int value = range[0]; value <= range[1]; value++) {
            assignment.put(name, value);
            expandCartesian(varNames, varRanges, depth + 1, assignment, onComplete);
        }
        assignment.remove(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> substituteVars(JsonNode template, Map<String, Integer> assignment) {
        Object raw = objectMapper.convertValue(template, Object.class);
        return (Map<String, Object>) substituteValue(raw, assignment);
    }

    private Object substituteValue(Object value, Map<String, Integer> assignment) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("$") && assignment.containsKey(trimmed.substring(1))) {
                return assignment.get(trimmed.substring(1));
            }
            String replaced = text;
            for (Map.Entry<String, Integer> entry : assignment.entrySet()) {
                replaced = replaced.replace("$" + entry.getKey(), String.valueOf(entry.getValue()));
            }
            return replaced;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), substituteValue(v, assignment)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(substituteValue(item, assignment));
            }
            return result;
        }
        return value;
    }

    private Map<String, Object> resolveReferences(Map<String, Object> data, Map<String, String> aliasToId) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        data.forEach((key, value) -> resolved.put(key, resolveValue(value, aliasToId)));
        return resolved;
    }

    private Object resolveValue(Object value, Map<String, String> aliasToId) {
        if (value instanceof String text && text.startsWith(REF_PREFIX)) {
            String alias = text.substring(REF_PREFIX.length());
            String resolvedId = aliasToId.get(alias);
            if (resolvedId == null) {
                throw new IllegalArgumentException(
                        "Unresolved $ref:" + alias + " -- alias not declared before this point in the seed file");
            }
            return resolvedId;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) objectMapper.convertValue(node, Object.class);
    }

    private static String requireConcept(JsonNode declared) {
        String concept = textOrNull(declared, "concept");
        if (concept == null) {
            throw new IllegalArgumentException("Seed record missing required 'concept'");
        }
        return concept;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }

    private record ExpandedRecord(String alias, String concept, String id, Map<String, Object> data) {
    }

    public static final class SeedNotFoundException extends RuntimeException {
        public SeedNotFoundException(String message) {
            super(message);
        }
    }

    public static final class SeedLoadException extends RuntimeException {
        public SeedLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record SeedRunResult(
            String seedId,
            String kind,
            Map<String, Integer> createdCounts,
            boolean ok,
            Integer failedRecordIndex,
            String failedAlias,
            String failedConcept,
            String failureMessage,
            long elapsedMs
    ) {
        static SeedRunResult success(String seedId, String kind, Map<String, Integer> createdCounts, long elapsedMs) {
            return new SeedRunResult(seedId, kind, createdCounts, true, null, null, null, null, elapsedMs);
        }

        static SeedRunResult failure(
                String seedId, String kind, Map<String, Integer> createdCounts,
                int failedRecordIndex, String failedAlias, String failedConcept, String failureMessage, long elapsedMs
        ) {
            return new SeedRunResult(seedId, kind, createdCounts, false, failedRecordIndex, failedAlias, failedConcept, failureMessage, elapsedMs);
        }
    }
}
