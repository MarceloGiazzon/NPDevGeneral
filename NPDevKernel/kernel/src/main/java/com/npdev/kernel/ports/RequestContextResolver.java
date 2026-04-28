package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;

import java.util.Map;

public interface RequestContextResolver {
    RequestContextResolver DEFAULT = (tenantIdHeader, actorIdHeader, headers) -> ExecutionContext.of(
            tenantIdHeader,
            actorIdHeader
    );

    ExecutionContext resolve(String tenantIdHeader, String actorIdHeader, Map<String, String> headers);
}
