package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompiledSchema {
    private final String type;
    private final Map<String, CompiledSchema> properties;
    private final CompiledSchema items;
    private final List<String> required;
    private final List<String> enumValues;
    private final Object defaultValue;
    private final String defaultExpression;
    private final String derivedExpression;
    private final String description;
    private final Integer minLength;
    private final Integer maxLength;
    private final Integer minItems;
    private final Integer maxItems;
    private final Boolean uniqueItems;
    private final String itemIdentityField;
    private final String duplicationPolicy;
    private final Double min;
    private final Double max;
    private final String regex;

    public CompiledSchema(
            String type,
            Map<String, CompiledSchema> properties,
            List<String> required,
            String description,
            Integer minLength,
            Integer maxLength,
            Double min,
            Double max,
            String regex
    ) {
        this(
                type,
                properties,
                null,
                required,
                List.of(),
                null,
                null,
                null,
                description,
                minLength,
                maxLength,
                null,
                null,
                null,
                null,
                null,
                min,
                max,
                regex
        );
    }

    public CompiledSchema(
            String type,
            Map<String, CompiledSchema> properties,
            CompiledSchema items,
            List<String> required,
            List<String> enumValues,
            Object defaultValue,
            String description,
            Integer minLength,
            Integer maxLength,
            Double min,
            Double max,
            String regex
    ) {
        this(
                type,
                properties,
                items,
                required,
                enumValues,
                defaultValue,
                null,
                null,
                description,
                minLength,
                maxLength,
                min,
                max,
                regex
        );
    }

    public CompiledSchema(
            String type,
            Map<String, CompiledSchema> properties,
            CompiledSchema items,
            List<String> required,
            List<String> enumValues,
            Object defaultValue,
            String description,
            Integer minLength,
            Integer maxLength,
            Integer minItems,
            Integer maxItems,
            Boolean uniqueItems,
            String itemIdentityField,
            String duplicationPolicy,
            Double min,
            Double max,
            String regex
    ) {
        this(
                type,
                properties,
                items,
                required,
                enumValues,
                defaultValue,
                null,
                null,
                description,
                minLength,
                maxLength,
                minItems,
                maxItems,
                uniqueItems,
                itemIdentityField,
                duplicationPolicy,
                min,
                max,
                regex
        );
    }

    public CompiledSchema(
            String type,
            Map<String, CompiledSchema> properties,
            CompiledSchema items,
            List<String> required,
            List<String> enumValues,
            Object defaultValue,
            String defaultExpression,
            String derivedExpression,
            String description,
            Integer minLength,
            Integer maxLength,
            Double min,
            Double max,
            String regex
    ) {
        this(
                type,
                properties,
                items,
                required,
                enumValues,
                defaultValue,
                defaultExpression,
                derivedExpression,
                description,
                minLength,
                maxLength,
                null,
                null,
                null,
                null,
                null,
                min,
                max,
                regex
        );
    }

    public CompiledSchema(
            String type,
            Map<String, CompiledSchema> properties,
            CompiledSchema items,
            List<String> required,
            List<String> enumValues,
            Object defaultValue,
            String defaultExpression,
            String derivedExpression,
            String description,
            Integer minLength,
            Integer maxLength,
            Integer minItems,
            Integer maxItems,
            Boolean uniqueItems,
            String itemIdentityField,
            String duplicationPolicy,
            Double min,
            Double max,
            String regex
    ) {
        this.type = type;
        this.properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.items = items;
        this.required = required == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(required));
        this.enumValues = enumValues == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(enumValues));
        this.defaultValue = defaultValue;
        this.defaultExpression = defaultExpression;
        this.derivedExpression = derivedExpression;
        this.description = description;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.minItems = minItems;
        this.maxItems = maxItems;
        this.uniqueItems = uniqueItems;
        this.itemIdentityField = itemIdentityField;
        this.duplicationPolicy = duplicationPolicy;
        this.min = min;
        this.max = max;
        this.regex = regex;
    }

    public String getType() {
        return type;
    }

    public Map<String, CompiledSchema> getProperties() {
        return properties;
    }

    public CompiledSchema getItems() {
        return items;
    }

    public List<String> getRequired() {
        return required;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public String getDefaultExpression() {
        return defaultExpression;
    }

    public String getDerivedExpression() {
        return derivedExpression;
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

    public Integer getMinItems() {
        return minItems;
    }

    public Integer getMaxItems() {
        return maxItems;
    }

    public Boolean getUniqueItems() {
        return uniqueItems;
    }

    public String getItemIdentityField() {
        return itemIdentityField;
    }

    public String getDuplicationPolicy() {
        return duplicationPolicy;
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
}
