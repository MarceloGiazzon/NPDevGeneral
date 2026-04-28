package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;

import java.util.Map;

public interface AuthenticatedContextResolver {
    AuthenticatedContextResolver DEFAULT = (claims, headers) -> ExecutionContext.anonymous();

    ExecutionContext resolveFromPrincipal(Map<String, Object> claims, Map<String, String> headers);
}
