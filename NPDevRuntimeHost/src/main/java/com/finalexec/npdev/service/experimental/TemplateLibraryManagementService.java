package com.finalexec.npdev.service.experimental;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.internal.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.TemplateLibraryRegistrationRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TemplateLibraryManagementService {

    private static final String CLASSPATH_LOCATION = "npdev-template-library/template-library-seed.json";
    private static final Path LIBRARY_ROOT = Paths.get("runtime-data", "template-library-records");

    private final ObjectMapper objectMapper;

    public TemplateLibraryManagementService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> seed = loadSeed();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("catalogPath", CLASSPATH_LOCATION);
        response.put("version", seed.getOrDefault("version", 1));
        response.put("templates", seed.getOrDefault("templates", List.of()));
        response.put("libraryStoragePath", LIBRARY_ROOT.toString().replace("\\", "/"));
        response.put("mode", "template-library-management-foundation");
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            // try-with-resources (QUAL-2): Files.list returns a Stream holding an open
            // DirectoryStream, and its javadoc requires closing it. On Windows a leaked directory
            // handle leaves the directory DELETE-PENDING, so its PARENT cannot be removed and the
            // error names the parent -- which is how the same defect in a test was misdiagnosed as
            // a JUnit @TempDir platform quirk for a morning (S1).
            if (Files.exists(LIBRARY_ROOT)) {
                try (var paths = Files.list(LIBRARY_ROOT)) {
                paths
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> item = objectMapper.readValue(path.toFile(), LinkedHashMap.class);
                                items.add(item);
                            } catch (Exception ignored) {
                            }
                        });
                }
            }
        } catch (Exception ignored) {
        }

        items.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault("registeredAt", "")),
                Comparator.reverseOrder()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> register(TemplateLibraryRegistrationRequest request) {
        validate(request);

        String recordId = UUID.randomUUID().toString();
        String registeredAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordId", recordId);
        record.put("templateId", request.getTemplateId());
        record.put("title", request.getTitle());
        record.put("versionTag", request.getVersionTag());
        record.put("owner", request.getOwner());
        record.put("lifecycleStage", request.getLifecycleStage());
        record.put("recommendationLevel", request.getRecommendationLevel());
        record.put("complianceNote", request.getComplianceNote());
        record.put("supportedOnboardingStyle", request.getSupportedOnboardingStyle());
        record.put("registeredAt", registeredAt);
        record.put("status", "REGISTERED");

        persistRecord(recordId, record);

        Map<String, Object> response = new LinkedHashMap<>(record);
        response.put("message", "Template library record registered.");
        return response;
    }

    private void validate(TemplateLibraryRegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTemplateId())) {
            throw new IllegalArgumentException("templateId is required.");
        }
        if (isBlank(request.getTitle())) {
            throw new IllegalArgumentException("title is required.");
        }
        if (isBlank(request.getVersionTag())) {
            throw new IllegalArgumentException("versionTag is required.");
        }
        if (isBlank(request.getOwner())) {
            throw new IllegalArgumentException("owner is required.");
        }
        if (isBlank(request.getLifecycleStage())) {
            throw new IllegalArgumentException("lifecycleStage is required.");
        }
        if (isBlank(request.getRecommendationLevel())) {
            throw new IllegalArgumentException("recommendationLevel is required.");
        }
        if (isBlank(request.getSupportedOnboardingStyle())) {
            throw new IllegalArgumentException("supportedOnboardingStyle is required.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> loadSeed() {
        try (InputStream inputStream = new ClassPathResource(CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load template library seed from classpath resource: " + CLASSPATH_LOCATION, e);
        }
    }

    private void persistRecord(String recordId, Map<String, Object> record) {
        try {
            Files.createDirectories(LIBRARY_ROOT);
            Path output = LIBRARY_ROOT.resolve(recordId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist template library record.", e);
        }
    }
}
