package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityAuthAst {
    private final List<String> roles;

    public CapabilityAuthAst(List<String> roles) {
        this.roles = new ArrayList<>(roles);
    }

    public List<String> getRoles() {
        return Collections.unmodifiableList(roles);
    }
}
