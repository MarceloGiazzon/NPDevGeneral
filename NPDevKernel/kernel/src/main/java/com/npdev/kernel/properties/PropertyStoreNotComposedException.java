package com.npdev.kernel.properties;

/**
 * REG-306 follow-up: thrown by {@link PropertyResolver#set} when the app declares {@code properties[]} but
 * never composed the {@code workspace::PropertyValue} concept (the {@code internal.tables} pack is off), so
 * there is no table a value could be stored in. Reads are unaffected -- they fall through to the declared
 * default; only a write has nowhere to go.
 */
public final class PropertyStoreNotComposedException extends RuntimeException {
    private final String code;

    public PropertyStoreNotComposedException(String propertyKey, String conceptName) {
        super("Property '" + propertyKey + "' cannot be set: this app does not compose the '" + conceptName
                + "' concept (enable internal.tables), so there is nowhere to store a value -- the declared default applies");
        this.code = "PROPERTY_STORE_NOT_COMPOSED";
    }

    public String code() {
        return code;
    }
}
