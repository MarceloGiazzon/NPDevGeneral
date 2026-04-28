package com.npdev.kernel.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchemaObject {
    private final String type;
    private final Map<String, SchemaObject> properties;
    private final List<String> required;
    private final String description;
    private final Integer minLength;
    private final Integer maxLength;
    private final Double min;
    private final Double max;
    private final String regex;

    public SchemaObject(
            String type,
            Map<String, SchemaObject> properties,
            List<String> required,
            String description,
            Integer minLength,
            Integer maxLength,
            Double min,
            Double max,
            String regex
    ) {
        this.type = normalize(type);
        this.properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.required = required == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(required));
        this.description = normalize(description);
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.min = min;
        this.max = max;
        this.regex = normalize(regex);
    }

    public String getType() {
        return type;
    }

    public Map<String, SchemaObject> getProperties() {
        return properties;
    }

    public List<String> getRequired() {
        return required;
    }

    public String getDescription() {
        return description;
    }

    public Integer getMinLength() {
        return minLength;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public Double getMin() {
        return min;
    }

    public Double getMax() {
        return max;
    }

    public String getRegex() {
        return regex;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
