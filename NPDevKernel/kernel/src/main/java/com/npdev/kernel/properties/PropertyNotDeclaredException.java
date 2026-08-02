package com.npdev.kernel.properties;

/**
 * The X0 rule (vector 11): an input {@link PropertyResolver} cannot handle is an error, never a
 * default answer. Thrown when {@code resolve}/{@code explain}/{@code set} names a property that is
 * not declared in the model's {@code properties[]}.
 */
public final class PropertyNotDeclaredException extends RuntimeException {
    private final String code;

    public PropertyNotDeclaredException(String propertyKey) {
        super("Property '" + propertyKey + "' is not declared in this model's properties[].");
        this.code = "PROPERTY_NOT_DECLARED";
    }

    public String code() {
        return code;
    }
}
