package com.npdev.runtime.support.crud.orchestration;

import com.npdev.dsl.v1.compiled.CompiledField;

import java.util.List;
import java.util.Map;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the resolved shape of a declarative
 * orchestrationRules {@code create} action, ready for row insertion.
 */
public record EventCreateOrchestration(
        String targetConcept,
        String targetTable,
        Map<String, CompiledField> fieldsByName,
        Map<String, String> fieldMap,
        List<String> uniqueFields
) {
}
