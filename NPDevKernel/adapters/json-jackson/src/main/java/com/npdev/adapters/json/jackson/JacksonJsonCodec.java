package com.npdev.adapters.json.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.kernel.ports.JsonCodec;

import java.util.Objects;

public final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper objectMapper;

    public JacksonJsonCodec() {
        // findAndRegisterModules() picks up jackson-datatype-jsr310 from the classpath, which lets
        // java.time types serialize at all -- but the module's own default for
        // WRITE_DATES_AS_TIMESTAMPS is true, so without explicitly disabling it an OffsetDateTime
        // round-trips through fromJsonToObject (target Object.class) as a bare epoch-seconds
        // BigDecimal, indistinguishable from any other number and useless to anything downstream
        // expecting a date. Confirmed live: a flow/event payload carrying a "datetime" field lost its
        // type this way and corrupted a second, capability-dispatched persistence write of the same
        // entity (the generated CRUD's own direct JDBC save used the correctly-typed DTO value and
        // succeeded; this codec's re-decoded copy did not).
        this(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    public JacksonJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed serializing JSON", exception);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed deserializing JSON to " + clazz.getName(), exception);
        }
    }

    @Override
    public Object fromJsonToObject(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed deserializing JSON into Object", exception);
        }
    }
}
