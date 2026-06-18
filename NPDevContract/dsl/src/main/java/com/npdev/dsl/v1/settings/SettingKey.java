package com.npdev.dsl.v1.settings;

import java.util.Objects;

/**
 * A declared, typed NPDev setting with a guaranteed platform default.
 *
 * <p>Every behaviour the platform exposes for personalization is registered as a
 * {@code SettingKey} so that (1) a default always exists and (2) override values
 * arriving from configuration or the model can be coerced into a known type rather
 * than passed around as untyped JSON.</p>
 *
 * @param <T> the resolved Java type of the setting value
 */
public final class SettingKey<T> {

    /** The value kinds a setting may hold. */
    public enum Type {
        BOOLEAN,
        STRING,
        INTEGER
    }

    private final String id;
    private final Type type;
    private final T defaultValue;
    private final String description;

    private SettingKey(String id, Type type, T defaultValue, String description) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Setting id must not be blank");
        }
        this.defaultValue = defaultValue;
        this.description = description == null ? "" : description;
    }

    public static SettingKey<Boolean> bool(String id, boolean defaultValue, String description) {
        return new SettingKey<>(id, Type.BOOLEAN, defaultValue, description);
    }

    public static SettingKey<String> string(String id, String defaultValue, String description) {
        return new SettingKey<>(id, Type.STRING, defaultValue, description);
    }

    public static SettingKey<Integer> integer(String id, int defaultValue, String description) {
        return new SettingKey<>(id, Type.INTEGER, defaultValue, description);
    }

    public String id() {
        return id;
    }

    public Type type() {
        return type;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }

    /**
     * Coerce a raw override value (possibly a {@code String} coming from JSON) into this
     * setting's declared type.
     *
     * @throws SettingResolutionException if the raw value cannot be coerced
     */
    @SuppressWarnings("unchecked")
    public T coerce(Object raw) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            return switch (type) {
                case BOOLEAN -> (T) coerceBoolean(raw);
                case STRING -> (T) raw.toString();
                case INTEGER -> (T) coerceInteger(raw);
            };
        } catch (SettingResolutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SettingResolutionException(
                    "Cannot coerce value '" + raw + "' for setting '" + id + "' to " + type, e);
        }
    }

    private Boolean coerceBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = raw.toString().trim();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new SettingResolutionException(
                "Setting '" + id + "' expects a boolean but got '" + raw + "'");
    }

    private Integer coerceInteger(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(raw.toString().trim());
    }

    @Override
    public String toString() {
        return "SettingKey[" + id + ":" + type + "]";
    }
}
