package com.npdev.kernel.ports;

import java.util.UUID;

@FunctionalInterface
public interface IdProvider {
    String nextId(String scope);

    static IdProvider uuid() {
        return scope -> UUID.randomUUID().toString();
    }
}
