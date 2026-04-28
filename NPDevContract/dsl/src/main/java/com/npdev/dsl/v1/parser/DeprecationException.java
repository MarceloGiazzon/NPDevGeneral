package com.npdev.dsl.v1.parser;

import java.io.IOException;

public final class DeprecationException extends IOException {

    public DeprecationException(String message) {
        super(message);
    }
}
