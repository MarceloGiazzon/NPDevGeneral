package com.npdev.kernel.storage.sql;

/**
 * Thrown when a storage engine is asked for something it does not support.
 *
 * <p><b>This class is the X0 rule made mechanical for the storage layer.</b> The alternative -- a
 * dialect method that quietly returns the Postgres answer, an empty string, or a no-op -- is the
 * silent-answer defect family in the least visible layer the platform has: it looks like success at
 * the call site and surfaces as missing data, weeks later, in production. Conformance vector C1
 * proves this is thrown; it is the most important vector in the suite, because every other one
 * proves a dialect CAN do something and only C1 proves it admits what it cannot.
 *
 * <p>The message always names the engine and the capability, so the reader never has to guess which
 * of the two to change.
 */
public class UnsupportedStorageCapabilityException extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    private final String engine;
    private final StorageCapability capability;

    public UnsupportedStorageCapabilityException(String engine, StorageCapability capability) {
        this(engine, capability, null);
    }

    public UnsupportedStorageCapabilityException(String engine, StorageCapability capability, String detail) {
        super(buildMessage(engine, capability, detail));
        this.engine = engine;
        this.capability = capability;
    }

    private static String buildMessage(String engine, StorageCapability capability, String detail) {
        StringBuilder message = new StringBuilder("storage engine '")
                .append(engine)
                .append("' does not support ")
                .append(capability);
        if (detail != null && !detail.isBlank()) {
            message.append(": ").append(detail);
        }
        return message.toString();
    }

    /** The engine that was asked, e.g. {@code "mysql"}. */
    public String engine() {
        return engine;
    }

    /** What it was asked for. */
    public StorageCapability capability() {
        return capability;
    }
}
