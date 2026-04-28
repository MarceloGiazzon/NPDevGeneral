package com.npdev.kernel.errors;

import com.npdev.kernel.CapabilityErrorKind;

public enum ErrorKind {
    INPUT_VALIDATION,
    INVARIANT_VIOLATION,
    CONTRACT,
    TRANSIENT,
    RATE_LIMIT,
    TIMEOUT,
    AUTH,
    FORBIDDEN,
    SYSTEM;

    public static ErrorKind fromCapabilityKind(CapabilityErrorKind capabilityErrorKind) {
        if (capabilityErrorKind == null) {
            return SYSTEM;
        }
        return switch (capabilityErrorKind) {
            case CONTRACT -> CONTRACT;
            case TRANSIENT -> TRANSIENT;
            case RATE_LIMIT -> RATE_LIMIT;
            case TIMEOUT -> TIMEOUT;
            case AUTH -> AUTH;
            case PERMANENT, NOT_FOUND -> SYSTEM;
        };
    }
}
