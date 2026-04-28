package com.finalexec.npdev.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginRepositoryDescriptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuntimePluginRepositoryCatalog {
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public RuntimePluginRepositoryCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RuntimePluginRepositoryDescriptor> loadCatalog() {
        List<RuntimePluginRepositoryDescriptor> repositories = new ArrayList<>();
        repositories.addAll(loadIndex("npdev/repositories/index.json"));
        return List.copyOf(repositories);
    }

    private List<RuntimePluginRepositoryDescriptor> loadIndex(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            List<Map<String, Object>> rows = objectMapper.readValue(inputStream, LIST_OF_MAP);
            List<RuntimePluginRepositoryDescriptor> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                out.add(toDescriptor(row));
            }
            return out;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load remote plugin repository catalog from " + path, exception);
        }
    }

    private RuntimePluginRepositoryDescriptor toDescriptor(Map<String, Object> row) {
        return new RuntimePluginRepositoryDescriptor(
                stringValue(row.get("repositoryId")),
                stringValue(row.get("displayName")),
                stringValue(row.get("repositoryType")),
                stringValue(row.get("endpoint")),
                stringValue(row.get("trustMode")),
                booleanValue(row.get("signatureRequired")),
                stringList(row.get("packageIds"))
        );
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        return List.of();
    }
}
