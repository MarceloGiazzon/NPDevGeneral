package com.finalexec.npdev.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class RuntimeMetadataService {

    private static final String ENDPOINT_VERSION = "1.0.0";
    private static final String COMPILED_METADATA_CLASSPATH = "npdev/compiled-metadata.json";
    private static final String METADATA_INDEX_CLASSPATH = "npdev/metadata/index.json";
    // F2.2: the same file SchemaLifecycleExecutor/SchemaManifestLoader read to get schemaFingerprint --
    // reused verbatim as the UI-contract bundle's modelHash rather than minting a second hash (the
    // plan's own explicit instruction). Note this fingerprint covers table/column/type/required/unique
    // shape only (see UserDatabaseDefinitionLoader#fingerprintInputs) -- it will NOT change for a
    // panel-action/permission-hint/flow/lifecycle-only edit, only for a schema-shaped one (e.g. a field
    // rename). Accepted boundary, not a bug: F4's drift detection is precise for renames, the category
    // this catalog exists to protect against.
    private static final String SCHEMA_REALIZATION_MANIFEST_CLASSPATH = "npdev/db/schema-realization-manifest.json";
    private static final Map<String, String> CATALOG_ALIASES = Map.ofEntries(
            Map.entry("concept", "concepts"),
            Map.entry("concepts", "concepts"),
            Map.entry("procedure", "procedures"),
            Map.entry("procedures", "procedures"),
            Map.entry("panel", "panels"),
            Map.entry("panels", "panels"),
            Map.entry("field", "fields"),
            Map.entry("fields", "fields"),
            Map.entry("enum", "enums"),
            Map.entry("enums", "enums"),
            Map.entry("reference", "references"),
            Map.entry("references", "references"),
            Map.entry("action", "actions"),
            Map.entry("actions", "actions"),
            Map.entry("transition", "transitions"),
            Map.entry("transitions", "transitions"),
            Map.entry("layout", "layout"),
            Map.entry("layouts", "layout"),
            Map.entry("validation", "validationHints"),
            Map.entry("validation-hints", "validationHints"),
            Map.entry("validationhints", "validationHints"),
            Map.entry("invocation", "invocations"),
            Map.entry("invocations", "invocations")
    );

    private final ObjectMapper objectMapper;

    public RuntimeMetadataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> overview() {
        Map<String, Object> compiledMetadata = loadJsonMap(COMPILED_METADATA_CLASSPATH);
        Map<String, Object> index = loadJsonMap(METADATA_INDEX_CLASSPATH);
        List<Map<String, Object>> catalogs = extractCatalogEntries(index);

        Map<String, Object> response = baseResponse();
        response.put("compiledMetadataPath", COMPILED_METADATA_CLASSPATH);
        response.put("metadataIndexPath", METADATA_INDEX_CLASSPATH);
        response.put("namespace", stringValue(compiledMetadata.get("namespace")));
        response.put("dslVersion", stringValue(compiledMetadata.get("dslVersion")));
        response.put("modelVersion", stringValue(compiledMetadata.get("version")));
        response.put("metadataManifestVersion", stringValue(index.get("metadataManifestVersion")));
        response.put("metadataVersion", stringValue(index.get("metadataVersion")));
        response.put("catalogCount", catalogs.size());
        response.put("catalogs", catalogs);
        response.put("compiledCatalogNames", extractCompiledCatalogNames(compiledMetadata));
        return response;
    }

    public Map<String, Object> metadataIndex() {
        Map<String, Object> index = new LinkedHashMap<>(loadJsonMap(METADATA_INDEX_CLASSPATH));
        index.put("endpointVersion", ENDPOINT_VERSION);
        index.put("catalogCount", extractCatalogEntries(index).size());
        index.put("metadataIndexPath", METADATA_INDEX_CLASSPATH);
        return index;
    }

    public Map<String, Object> concepts(String conceptName) {
        return buildCatalogResponse("concepts", conceptName, null, null);
    }

    public Map<String, Object> concept(String conceptName) {
        Map<String, Object> response = buildCatalogResponse("concepts", conceptName, null, null);
        List<Map<String, Object>> items = extractItems(response);
        if (items.isEmpty()) {
            throw new NoSuchElementException("Runtime metadata concept not found: " + conceptName);
        }
        response.put("concept", items.get(0));
        response.put("relatedCatalogCounts", relatedCatalogCounts(conceptName));
        return response;
    }

    public Map<String, Object> procedures(String procedureName) {
        return buildCatalogResponse("procedures", null, procedureName, null);
    }

    public Map<String, Object> panels(String panelName) {
        return buildCatalogResponse("panels", null, panelName, null);
    }

    public Map<String, Object> fields(String conceptName, String fieldPath) {
        return buildCatalogResponse("fields", conceptName, null, fieldPath);
    }

    public Map<String, Object> enums(String conceptName, String fieldPath) {
        Map<String, Object> response = buildCatalogResponse("enums", conceptName, null, fieldPath);
        response.put("enumFields", distinctValues(extractItems(response), "fieldPath"));
        return response;
    }

    public Map<String, Object> references(String conceptName, String fieldPath) {
        Map<String, Object> response = buildCatalogResponse("references", conceptName, null, fieldPath);
        response.put("targetConcepts", distinctValues(extractItems(response), "targetConcept"));
        return response;
    }

    public Map<String, Object> actions(String conceptName, String ownerName) {
        Map<String, Object> response = buildCatalogResponse("actions", conceptName, ownerName, null);
        response.put("actionKinds", distinctValues(extractItems(response), "kind"));
        response.put("permissionHints", distinctValues(extractItems(response), "permissionHint"));
        return response;
    }

    public Map<String, Object> layout(String conceptName, String fieldPath) {
        Map<String, Object> response = buildCatalogResponse("layout", conceptName, null, fieldPath);
        response.put("tabs", distinctValues(extractItems(response), "tab"));
        return response;
    }

    public Map<String, Object> validationSupport(String conceptName, String fieldPath) {
        Map<String, Object> response = buildCatalogResponse("validationHints", conceptName, null, fieldPath);
        List<Map<String, Object>> items = extractItems(response);
        response.put("hintKinds", distinctValues(items, "kind"));
        response.put("kindCounts", countBy(items, "kind"));
        return response;
    }

    public Map<String, Object> catalog(String catalogName, String conceptName, String ownerName, String fieldPath) {
        return buildCatalogResponse(catalogName, conceptName, ownerName, fieldPath);
    }

    /** F2.2: the UI-contract bundle's {@code modelHash} -- the same fingerprint
     * {@code SchemaLifecycleExecutor}/{@code SchemaManifestLoader} read from this identical classpath
     * resource, reused rather than minting a second hash. Throws {@link IllegalStateException} (like
     * every other catalog read here) if the app has no schema-realization manifest on its classpath --
     * the controller's existing {@code run()} wrapper maps that to 503, same as a missing catalog. */
    public String schemaFingerprint() {
        return stringValue(loadJsonMap(SCHEMA_REALIZATION_MANIFEST_CLASSPATH).get("schemaFingerprint"));
    }

    public Map<String, Object> previewSupport(String conceptName) {
        Map<String, Object> conceptResponse = concept(conceptName);
        Map<String, Object> concept = castMap(conceptResponse.get("concept"));
        List<Map<String, Object>> fieldItems = filteredItems("fields", conceptName, null, null);
        List<Map<String, Object>> enumItems = filteredItems("enums", conceptName, null, null);
        List<Map<String, Object>> referenceItems = filteredItems("references", conceptName, null, null);
        List<Map<String, Object>> actionItems = filteredItems("actions", conceptName, null, null);
        List<Map<String, Object>> layoutItems = filteredItems("layout", conceptName, null, null);
        List<Map<String, Object>> validationItems = filteredItems("validationHints", conceptName, null, null);

        Map<String, Object> response = baseResponse();
        response.put("concept", concept);
        response.put("relatedCatalogCounts", relatedCatalogCounts(conceptName));
        response.put("fields", fieldItems);
        response.put("enums", enumItems);
        response.put("references", referenceItems);
        response.put("actions", actionItems);
        response.put("layout", layoutItems);
        response.put("validationHints", validationItems);

        Map<String, Object> previewSupport = new LinkedHashMap<>();
        previewSupport.put("tabs", distinctValues(layoutItems, "tab"));
        previewSupport.put("summaryFields", summaryFields(layoutItems));
        previewSupport.put("listColumns", listColumns(layoutItems));
        previewSupport.put("referencePickers", referencePickers(referenceItems));
        previewSupport.put("actionLabels", actionSummaries(actionItems));
        previewSupport.put("validationKinds", distinctValues(validationItems, "kind"));
        previewSupport.put("defaultSort", stringValue(concept.get("defaultSort")));
        previewSupport.put("defaultGroup", stringValue(concept.get("defaultGroup")));
        previewSupport.put("displayMode", stringValue(concept.get("displayMode")));
        response.put("previewSupport", previewSupport);
        return response;
    }

    private Map<String, Object> buildCatalogResponse(
            String requestedCatalog,
            String conceptName,
            String ownerName,
            String fieldPath
    ) {
        String resolvedCatalog = normalizeCatalogName(requestedCatalog);
        Map<String, Object> manifest = new LinkedHashMap<>(loadManifest(resolvedCatalog));
        List<Map<String, Object>> filteredItems = filteredItems(resolvedCatalog, conceptName, ownerName, fieldPath);

        manifest.put("endpointVersion", ENDPOINT_VERSION);
        manifest.put("requestedCatalog", requestedCatalog);
        manifest.put("resolvedCatalog", resolvedCatalog);
        manifest.put("filters", buildFilters(conceptName, ownerName, fieldPath));
        manifest.put("filteredCount", filteredItems.size());
        manifest.put("items", filteredItems);
        return manifest;
    }

    private Map<String, Object> buildFilters(String conceptName, String ownerName, String fieldPath) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (!normalize(conceptName).isBlank()) {
            filters.put("concept", conceptName.trim());
        }
        if (!normalize(ownerName).isBlank()) {
            filters.put("ownerName", ownerName.trim());
        }
        if (!normalize(fieldPath).isBlank()) {
            filters.put("fieldPath", fieldPath.trim());
        }
        return filters;
    }

    private Map<String, Object> relatedCatalogCounts(String conceptName) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("fields", filteredItems("fields", conceptName, null, null).size());
        counts.put("enums", filteredItems("enums", conceptName, null, null).size());
        counts.put("references", filteredItems("references", conceptName, null, null).size());
        counts.put("actions", filteredItems("actions", conceptName, null, null).size());
        counts.put("layout", filteredItems("layout", conceptName, null, null).size());
        counts.put("validationHints", filteredItems("validationHints", conceptName, null, null).size());
        return counts;
    }

    private List<Map<String, Object>> filteredItems(
            String catalogName,
            String conceptName,
            String ownerName,
            String fieldPath
    ) {
        List<Map<String, Object>> sourceItems = extractItems(loadManifest(normalizeCatalogName(catalogName)));
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : sourceItems) {
            if (!matchesConcept(catalogName, item, conceptName)) {
                continue;
            }
            if (!matchesFieldPath(item, fieldPath)) {
                continue;
            }
            if (!matchesOwnerName(item, ownerName)) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    private boolean matchesConcept(String catalogName, Map<String, Object> item, String conceptName) {
        String normalizedConcept = normalize(conceptName);
        if (normalizedConcept.isBlank()) {
            return true;
        }
        String property = "concepts".equals(normalizeCatalogName(catalogName)) ? "name" : "concept";
        return normalize(item.get(property)).equalsIgnoreCase(normalizedConcept);
    }

    private boolean matchesFieldPath(Map<String, Object> item, String fieldPath) {
        String normalizedFieldPath = normalize(fieldPath);
        if (normalizedFieldPath.isBlank()) {
            return true;
        }
        return normalize(item.get("fieldPath")).equalsIgnoreCase(normalizedFieldPath);
    }

    private boolean matchesOwnerName(Map<String, Object> item, String ownerName) {
        String normalizedOwnerName = normalize(ownerName);
        if (normalizedOwnerName.isBlank()) {
            return true;
        }
        return normalize(item.get("ownerName")).equalsIgnoreCase(normalizedOwnerName);
    }

    private String normalizeCatalogName(String catalogName) {
        String normalized = normalize(catalogName).toLowerCase().replace("_", "-");
        String resolved = CATALOG_ALIASES.get(normalized);
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported runtime metadata catalog: " + catalogName);
        }
        return resolved;
    }

    private Map<String, Object> loadManifest(String catalogName) {
        Map<String, Object> index = loadJsonMap(METADATA_INDEX_CLASSPATH);
        List<Map<String, Object>> catalogs = extractCatalogEntries(index);
        for (Map<String, Object> catalog : catalogs) {
            if (normalize(catalog.get("name")).equalsIgnoreCase(catalogName)) {
                String path = stringValue(catalog.get("path"));
                if (path.isBlank()) {
                    throw new IllegalStateException("Runtime metadata catalog path is blank for catalog: " + catalogName);
                }
                return loadJsonMap(path);
            }
        }
        throw new IllegalStateException("Runtime metadata index does not expose catalog: " + catalogName);
    }

    private List<Map<String, Object>> extractCatalogEntries(Map<String, Object> index) {
        List<Map<String, Object>> catalogs = new ArrayList<>();
        Object raw = index.get("catalogs");
        if (raw instanceof Collection<?> collection) {
            for (Object entry : collection) {
                catalogs.add(castMap(entry));
            }
        }
        return catalogs;
    }

    private Set<String> extractCompiledCatalogNames(Map<String, Object> compiledMetadata) {
        Set<String> names = new LinkedHashSet<>();
        Object raw = compiledMetadata.get("catalogs");
        if (raw instanceof Map<?, ?> catalogMap) {
            for (Object key : catalogMap.keySet()) {
                names.add(String.valueOf(key));
            }
        }
        return names;
    }

    private List<Map<String, Object>> extractItems(Map<String, Object> manifest) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object rawItems = manifest.get("items");
        if (rawItems instanceof Collection<?> collection) {
            for (Object item : collection) {
                items.add(castMap(item));
            }
        }
        return items;
    }

    private List<String> distinctValues(List<Map<String, Object>> items, String key) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            String value = stringValue(item.get(key));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private Map<String, Integer> countBy(List<Map<String, Object>> items, String key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String value = stringValue(item.get(key));
            if (value.isBlank()) {
                value = "<blank>";
            }
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }
        return counts;
    }

    private List<String> summaryFields(List<Map<String, Object>> layoutItems) {
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> item : layoutItems) {
            Object summaryCard = item.get("summaryCard");
            if (Boolean.TRUE.equals(summaryCard)) {
                String fieldPath = stringValue(item.get("fieldPath"));
                if (!fieldPath.isBlank()) {
                    fields.add(fieldPath);
                }
            }
        }
        return fields;
    }

    private List<Map<String, Object>> listColumns(List<Map<String, Object>> layoutItems) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> item : layoutItems) {
            if (!Boolean.TRUE.equals(item.get("listColumn"))) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("fieldPath", stringValue(item.get("fieldPath")));
            summary.put("label", stringValue(item.get("label")));
            summary.put("listColumnOrder", item.get("listColumnOrder"));
            columns.add(summary);
        }
        columns.sort((left, right) -> Integer.compare(orderValue(left.get("listColumnOrder")), orderValue(right.get("listColumnOrder"))));
        return columns;
    }

    private List<Map<String, Object>> referencePickers(List<Map<String, Object>> referenceItems) {
        List<Map<String, Object>> pickers = new ArrayList<>();
        for (Map<String, Object> item : referenceItems) {
            Map<String, Object> picker = new LinkedHashMap<>();
            picker.put("fieldPath", stringValue(item.get("fieldPath")));
            picker.put("targetConcept", stringValue(item.get("targetConcept")));
            picker.put("displayTemplate", stringValue(item.get("displayTemplate")));
            picker.put("pickerColumns", item.getOrDefault("pickerColumns", List.of()));
            picker.put("previewCardTemplate", stringValue(item.get("previewCardTemplate")));
            picker.put("defaultFilter", stringValue(item.get("defaultFilter")));
            picker.put("inlineCreate", stringValue(item.get("inlineCreate")));
            pickers.add(picker);
        }
        return pickers;
    }

    private List<Map<String, Object>> actionSummaries(List<Map<String, Object>> actionItems) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map<String, Object> item : actionItems) {
            String label = stringValue(item.get("label"));
            if (label.isBlank()) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", stringValue(item.get("name")));
            summary.put("kind", stringValue(item.get("kind")));
            summary.put("label", label);
            summary.put("permissionHint", stringValue(item.get("permissionHint")));
            summary.put("dangerLevel", stringValue(item.get("dangerLevel")));
            summary.put("inputFormHint", stringValue(item.get("inputFormHint")));
            summaries.add(summary);
        }
        return summaries;
    }

    private int orderValue(Object rawValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(rawValue));
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private Map<String, Object> baseResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("sourceType", "generated-runtime-metadata");
        response.put("sourceRoot", "classpath:/npdev");
        response.put("generatedFrom", COMPILED_METADATA_CLASSPATH);
        return response;
    }

    private Map<String, Object> loadJsonMap(String classpathLocation) {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load runtime metadata classpath resource: " + classpathLocation, e);
        }
    }

    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        return normalize(value);
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
