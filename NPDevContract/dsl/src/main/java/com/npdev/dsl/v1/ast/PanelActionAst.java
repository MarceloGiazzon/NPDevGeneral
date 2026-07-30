package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record PanelActionAst(
        String name,
        String label,
        String binding,
        String concept,
        String operation,
        String procedure,
        String flow,
        String visibleWhen,
        String enabledWhen,
        List<String> permissionRequirements,
        Map<String, Object> explainability,
        Map<String, Object> metadata,
        String scope,
        String dataSource,
        List<String> inputFields,
        String resultAs,
        String filename,
        String contentType
) {
    public PanelActionAst {
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        explainability = explainability == null ? Map.of() : Map.copyOf(explainability);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        resultAs = resultAs == null || resultAs.isBlank() ? null : resultAs.trim();
        filename = filename == null || filename.isBlank() ? null : filename.trim();
        contentType = contentType == null || contentType.isBlank() ? null : contentType.trim();
    }
}
