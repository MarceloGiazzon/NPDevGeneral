package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledCapabilityAuth {
    private final List<String> roles;

    public CompiledCapabilityAuth(List<String> roles) {
        this.roles = new ArrayList<>(roles);
    }

    public List<String> getRoles() {
        return Collections.unmodifiableList(roles);
    }
}
