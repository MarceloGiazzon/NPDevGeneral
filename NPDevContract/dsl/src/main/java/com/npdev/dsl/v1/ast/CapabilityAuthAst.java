package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityAuthAst {
    private final List<String> roles;
    private final List<String> scopes;

    public CapabilityAuthAst(List<String> roles, List<String> scopes) {
        this.roles = new ArrayList<>(roles);
        this.scopes = new ArrayList<>(scopes);
    }

    public List<String> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    public List<String> getScopes() {
        return Collections.unmodifiableList(scopes);
    }
}
