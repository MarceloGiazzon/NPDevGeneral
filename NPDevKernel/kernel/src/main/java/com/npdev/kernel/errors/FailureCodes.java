package com.npdev.kernel.errors;

public final class FailureCodes {
    public static final String INPUT_VALIDATION_FAILED = "input_validation_failed";
    public static final String INVARIANT_VIOLATION = "invariant_violation";
    public static final String CAPABILITY_CONTRACT = "capability_contract";
    public static final String CAPABILITY_TRANSIENT = "capability_transient";
    public static final String CAPABILITY_RATE_LIMITED = "capability_rate_limited";
    public static final String CAPABILITY_TIMEOUT = "capability_timeout";
    public static final String CAPABILITY_AUTH = "capability_auth";
    public static final String CAPABILITY_NOT_FOUND = "capability_not_found";
    public static final String CIRCUIT_OPEN = "circuit_open";
    public static final String BULKHEAD_FULL = "bulkhead_full";
    public static final String IDEMPOTENCY_HIT_FAILED = "idempotency_hit_failed";
    public static final String CORRELATION_OWNER_CONFLICT = "correlation_owner_conflict";
    public static final String FORBIDDEN = "forbidden";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String RESUME_ATTEMPT_CAP = "resume_attempt_cap";
    public static final String EVENT_PAYLOAD_INVALID = "event_payload_invalid";
    public static final String SYSTEM_EXCEPTION = "system_exception";

    private FailureCodes() {
    }
}
