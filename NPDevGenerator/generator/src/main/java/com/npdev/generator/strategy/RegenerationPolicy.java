package com.npdev.generator.strategy;

import java.nio.file.Path;

public final class RegenerationPolicy {

    public enum Mode {
        GENERATED,
        CUSTOM_STUBS
    }

    private final Mode mode;

    public RegenerationPolicy() {
        this(Mode.GENERATED);
    }

    public RegenerationPolicy(Mode mode) {
        this.mode = mode;
    }

    public boolean canOverwrite(Path targetFile) {
        if (mode == Mode.CUSTOM_STUBS) {
            // Custom extension files must never overwrite user-owned code.
            return false;
        }

        // Generated mode: safe to overwrite because output repo is cleaned.
        return true;
    }
}
