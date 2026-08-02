package com.npdev.kernel.properties;

import java.util.List;

/**
 * Vector 13: {@code settableAt} is enforced on WRITE, not on read -- "a control a user can switch off
 * is not a control." Thrown by {@link PropertyResolver#set} when the target scope is not one of the
 * property's declared {@code settableAt} values.
 */
public final class PropertyNotSettableAtScopeException extends RuntimeException {
    private final String code;

    public PropertyNotSettableAtScopeException(String propertyKey, String scopeType, List<String> settableAt) {
        super("Property '" + propertyKey + "' is not settable at scope '" + scopeType
                + "' -- declared settableAt: " + settableAt);
        this.code = "PROPERTY_NOT_SETTABLE_AT_SCOPE";
    }

    public String code() {
        return code;
    }
}
