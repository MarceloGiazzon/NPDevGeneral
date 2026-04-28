package com.npdev.kernel.ports;

public interface JsonCodec {
    String toJson(Object value);

    <T> T fromJson(String json, Class<T> clazz);

    Object fromJsonToObject(String json);

    static JsonCodec noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final JsonCodec INSTANCE = new JsonCodec() {
            @Override
            public String toJson(Object value) {
                return value == null ? "null" : String.valueOf(value);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T fromJson(String json, Class<T> clazz) {
                if (clazz == null) {
                    throw new IllegalArgumentException("clazz must be non-null");
                }
                if (json == null) {
                    return null;
                }
                if (clazz.isAssignableFrom(String.class)) {
                    return (T) json;
                }
                throw new IllegalStateException("JsonCodec.noop cannot deserialize into " + clazz.getName());
            }

            @Override
            public Object fromJsonToObject(String json) {
                return json;
            }
        };

        private NoopHolder() {
        }
    }
}
