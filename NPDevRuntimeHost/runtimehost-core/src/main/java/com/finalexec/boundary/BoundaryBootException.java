package com.finalexec.boundary;

/**
 * Wave 2: boot-time boundary violation. Thrown when a boundary enforcement point refuses at
 * boot (B4 migration lock, B5 schema-ahead, B8 ownership history). The global exception
 * handler converts this into a 503 JSON response carrying the {@link BoundaryViolation}.
 */
public class BoundaryBootException extends RuntimeException {
    private final BoundaryViolation violation;

    public BoundaryBootException(BoundaryViolation violation) {
        super(violation.message());
        this.violation = violation;
    }

    public BoundaryBootException(BoundaryViolation violation, Throwable cause) {
        super(violation.message(), cause);
        this.violation = violation;
    }

    public BoundaryViolation getViolation() {
        return violation;
    }
}
