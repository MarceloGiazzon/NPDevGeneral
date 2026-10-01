package com.npdev.dsl.v1.gpucheck;

/** GPU-1 (G2): thrown when a check's tree cannot be reproduced exactly on the GPU/CPU-twin engine
 *  -- the builder catches this and records the check as {@code skipped} with {@link #getMessage()}
 *  as the plain-language reason, rather than approximating. */
public final class GpuUnsupportedException extends RuntimeException {
    public GpuUnsupportedException(String reason) {
        super(reason);
    }
}
