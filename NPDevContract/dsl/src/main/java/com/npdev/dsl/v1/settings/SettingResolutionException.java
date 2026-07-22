package com.npdev.dsl.v1.settings;

/** Raised when a setting value cannot be coerced into its declared type or otherwise resolved. */
public final class SettingResolutionException extends RuntimeException {

    public SettingResolutionException(String message) {
        super(message);
    }

    public SettingResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
